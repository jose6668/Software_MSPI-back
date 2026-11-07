ALTER TABLE comun.invitaciones ADD UNIQUE (empresa_id, id);

ALTER TABLE comun.empresas
    ADD COLUMN descripcion text,
    ADD COLUMN contacto_inicial varchar(254),
    ADD COLUMN clave_contacto_inicial varchar(254),
    ADD COLUMN creado_por uuid REFERENCES comun.usuarios(id),
    ADD COLUMN clave_alta uuid,
    ADD COLUMN invitacion_inicial_id uuid,
    ADD COLUMN referencia_fallo uuid,
    ADD COLUMN motivo_estado varchar(1000),
    ADD CONSTRAINT empresa_alta_idempotente UNIQUE (creado_por, clave_alta),
    ADD CONSTRAINT empresa_invitacion_inicial_local FOREIGN KEY (id, invitacion_inicial_id)
        REFERENCES comun.invitaciones(empresa_id, id),
    ADD CHECK ((creado_por IS NULL) = (clave_alta IS NULL)),
    ADD CHECK (creado_por IS NULL OR (contacto_inicial IS NOT NULL AND clave_contacto_inicial IS NOT NULL));

REVOKE SELECT ON comun.empresas FROM "${runtime_role}";
GRANT SELECT (id, nombre, zona_horaria, esquema_interno, estado, version_estructura, revision_registro, creado_en)
    ON comun.empresas TO "${runtime_role}";

CREATE TABLE comun.eventos_plataforma (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    empresa_id uuid NOT NULL REFERENCES comun.empresas(id),
    actor_id uuid NOT NULL REFERENCES comun.usuarios(id),
    operacion text NOT NULL CHECK (operacion IN ('RESERVAR', 'REINTENTAR', 'ACTIVAR',
        'FALLO_ALTA', 'REEMITIR_INICIAL', 'SUSPENDER', 'REACTIVAR')),
    estado_anterior text,
    estado_nuevo text NOT NULL,
    motivo varchar(1000),
    correlacion_id uuid NOT NULL,
    registrado_en timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX eventos_plataforma_por_empresa ON comun.eventos_plataforma (empresa_id, id);

-- Private helper, invoked only by functions owned by the common migrator.
CREATE FUNCTION comun.exigir_ap(p_actor uuid)
RETURNS void LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM 1 FROM comun.atribuciones_plataforma ap JOIN comun.usuarios u ON u.id = ap.usuario_id
    WHERE ap.usuario_id = p_actor AND ap.codigo = 'AP' AND u.estado_cuenta = 'HABILITADA'
    FOR SHARE OF ap, u;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Operación de plataforma no autorizada.' USING ERRCODE = 'P1001';
    END IF;
END;
$$;

CREATE FUNCTION comun.empresas_de_plataforma(p_actor uuid, p_empresa uuid, p_limit integer, p_offset bigint)
RETURNS TABLE (id uuid, nombre text, descripcion text, zona_horaria text, estado text, version_estructura text,
               revision_registro bigint, paso_preparacion text, resultado_preparacion text, referencia_fallo uuid,
               motivo_estado text, invitacion_inicial_id uuid)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM comun.exigir_ap(p_actor);
    IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 101 OR p_offset IS NULL OR p_offset < 0 THEN
        RAISE EXCEPTION 'Paginación inválida.' USING ERRCODE = '22023';
    END IF;
    RETURN QUERY SELECT e.id, e.nombre, e.descripcion, e.zona_horaria, e.estado, e.version_estructura,
        e.revision_registro, i.paso, i.resultado,
        coalesce(e.referencia_fallo, CASE WHEN i.resultado = 'FALLIDO' THEN i.id END),
        e.motivo_estado::text, e.invitacion_inicial_id
    FROM comun.empresas e
    LEFT JOIN LATERAL (
        SELECT p.id, p.paso, p.resultado FROM comun.intentos_preparacion p
        WHERE p.empresa_id = e.id ORDER BY p.iniciado_en DESC, p.id DESC LIMIT 1
    ) i ON true
    WHERE p_empresa IS NULL OR e.id = p_empresa
    ORDER BY e.creado_en DESC, e.id LIMIT p_limit OFFSET p_offset;
END;
$$;

CREATE FUNCTION comun.reservar_empresa(p_actor uuid, p_empresa uuid, p_clave uuid, p_nombre text,
    p_descripcion text, p_zona text, p_correo text, p_clave_correo text, p_correlacion uuid)
RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_id uuid;
BEGIN
    PERFORM comun.exigir_ap(p_actor);
    INSERT INTO comun.empresas(id, nombre, descripcion, zona_horaria, esquema_interno,
                               contacto_inicial, clave_contacto_inicial, creado_por, clave_alta)
    VALUES (p_empresa, p_nombre, p_descripcion, p_zona, 'emp_' || replace(p_empresa::text, '-', ''),
            p_correo, p_clave_correo, p_actor, p_clave)
    ON CONFLICT (creado_por, clave_alta) DO NOTHING RETURNING id INTO v_id;
    IF FOUND THEN
        INSERT INTO comun.eventos_plataforma(empresa_id, actor_id, operacion, estado_nuevo, correlacion_id)
        VALUES (v_id, p_actor, 'RESERVAR', 'EN_PREPARACION', p_correlacion);
    ELSE
        SELECT e.id INTO v_id FROM comun.empresas e
        WHERE e.creado_por = p_actor AND e.clave_alta = p_clave AND e.nombre = p_nombre
          AND e.descripcion IS NOT DISTINCT FROM p_descripcion AND e.zona_horaria = p_zona
          AND e.contacto_inicial = p_correo;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Reserva en conflicto.' USING ERRCODE = 'P1002';
        END IF;
    END IF;
    RETURN v_id;
END;
$$;

CREATE FUNCTION comun.cambiar_disponibilidad_empresa(p_actor uuid, p_empresa uuid, p_revision bigint,
    p_reactivar boolean, p_motivo text, p_version text, p_correlacion uuid)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_empresa comun.empresas%ROWTYPE;
    v_destino text := CASE WHEN p_reactivar THEN 'ACTIVA' ELSE 'SUSPENDIDA' END;
BEGIN
    PERFORM comun.exigir_ap(p_actor);
    SELECT e.* INTO v_empresa FROM comun.empresas e WHERE e.id = p_empresa FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Empresa no disponible.' USING ERRCODE = 'P1003'; END IF;
    IF v_empresa.revision_registro IS DISTINCT FROM p_revision OR p_reactivar IS NULL
        OR p_motivo IS NULL OR btrim(p_motivo) = '' THEN
        RAISE EXCEPTION 'La revisión o el motivo no es válido.' USING ERRCODE = 'P1002';
    END IF;
    IF NOT p_reactivar AND v_empresa.estado = 'SUSPENDIDA' THEN RETURN true; END IF;
    IF (p_reactivar AND (v_empresa.estado <> 'SUSPENDIDA' OR v_empresa.version_estructura IS DISTINCT FROM p_version))
       OR (NOT p_reactivar AND v_empresa.estado <> 'ACTIVA') THEN
        RAISE EXCEPTION 'La empresa no admite el cambio.' USING ERRCODE = 'P1002';
    END IF;
    UPDATE comun.empresas SET estado = v_destino, motivo_estado = p_motivo,
        revision_registro = revision_registro + 1 WHERE id = p_empresa;
    INSERT INTO comun.eventos_plataforma(empresa_id, actor_id, operacion, estado_anterior, estado_nuevo,
                                       motivo, correlacion_id)
    VALUES (p_empresa, p_actor, CASE WHEN p_reactivar THEN 'REACTIVAR' ELSE 'SUSPENDER' END,
            v_empresa.estado, v_destino, p_motivo, p_correlacion);
    RETURN true;
END;
$$;

CREATE FUNCTION comun.activar_con_invitacion_inicial(p_actor uuid, p_empresa uuid, p_revision bigint,
    p_invitacion uuid, p_huella bytea, p_segundos integer, p_version text,
    p_reemitir boolean, p_correlacion uuid)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_empresa comun.empresas%ROWTYPE;
    v_anterior comun.invitaciones%ROWTYPE;
    v_resultado text;
    v_now timestamptz;
BEGIN
    PERFORM comun.exigir_ap(p_actor);
    SELECT e.* INTO v_empresa FROM comun.empresas e WHERE e.id = p_empresa FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Empresa no disponible.' USING ERRCODE = 'P1003'; END IF;
    IF v_empresa.revision_registro IS DISTINCT FROM p_revision OR v_empresa.version_estructura IS DISTINCT FROM p_version
        OR v_empresa.contacto_inicial IS NULL OR p_segundos IS NULL OR p_segundos < 1 OR p_reemitir IS NULL THEN
        RAISE EXCEPTION 'Alta incompatible o revisión obsoleta.' USING ERRCODE = 'P1002';
    END IF;

    v_now := clock_timestamp();
    IF p_reemitir THEN
        SELECT i.* INTO v_anterior FROM comun.invitaciones i
        WHERE i.id = v_empresa.invitacion_inicial_id AND i.empresa_id = p_empresa FOR UPDATE;
        IF NOT FOUND OR v_empresa.estado <> 'ACTIVA' OR v_anterior.aceptada_en IS NOT NULL
            OR EXISTS (
                SELECT 1 FROM comun.membresias m JOIN comun.membresia_roles r ON r.membresia_id = m.id
                WHERE m.empresa_id = p_empresa AND m.estado = 'ACTIVA' AND r.rol_codigo = 'AE'
            ) THEN
            RAISE EXCEPTION 'La invitación inicial no admite reemisión.' USING ERRCODE = 'P1002';
        END IF;
        IF v_anterior.revocada_en IS NULL AND v_now < v_anterior.vence_en THEN
            UPDATE comun.invitaciones SET revocada_en = v_now, revocada_por = p_actor WHERE id = v_anterior.id;
        END IF;
    ELSE
        SELECT p.resultado INTO v_resultado FROM comun.intentos_preparacion p
        WHERE p.empresa_id = p_empresa AND p.version_objetivo = p_version
        ORDER BY p.iniciado_en DESC, p.id DESC LIMIT 1;
        IF v_empresa.estado NOT IN ('EN_PREPARACION', 'ERROR_APROVISIONAMIENTO')
            OR v_empresa.invitacion_inicial_id IS NOT NULL OR v_resultado IS DISTINCT FROM 'VERIFICADO' THEN
            RAISE EXCEPTION 'La preparación técnica no está verificada.' USING ERRCODE = 'P1002';
        END IF;
        IF v_empresa.estado = 'ERROR_APROVISIONAMIENTO' THEN
            INSERT INTO comun.eventos_plataforma(empresa_id, actor_id, operacion, estado_anterior,
                                                 estado_nuevo, correlacion_id)
            VALUES (p_empresa, p_actor, 'REINTENTAR', v_empresa.estado, 'EN_PREPARACION', p_correlacion);
        END IF;
    END IF;

    INSERT INTO comun.invitaciones(id, empresa_id, emisor_id, correo_destinatario, clave_destinatario,
                                   huella_token, creada_en, vence_en)
    VALUES (p_invitacion, p_empresa, p_actor, v_empresa.contacto_inicial, v_empresa.clave_contacto_inicial, p_huella,
            v_now, v_now + make_interval(secs => p_segundos));
    INSERT INTO comun.invitacion_roles(invitacion_id, rol_codigo) VALUES (p_invitacion, 'AE');
    UPDATE comun.empresas SET estado = 'ACTIVA', invitacion_inicial_id = p_invitacion,
        referencia_fallo = NULL, revision_registro = revision_registro + 1 WHERE id = p_empresa;
    INSERT INTO comun.eventos_plataforma(empresa_id, actor_id, operacion, estado_anterior, estado_nuevo, correlacion_id)
    VALUES (p_empresa, p_actor, CASE WHEN p_reemitir THEN 'REEMITIR_INICIAL' ELSE 'ACTIVAR' END,
            CASE WHEN p_reemitir THEN 'ACTIVA' ELSE 'EN_PREPARACION' END, 'ACTIVA', p_correlacion);
    RETURN v_empresa.contacto_inicial;
END;
$$;

CREATE FUNCTION comun.registrar_fallo_de_alta(p_actor uuid, p_empresa uuid, p_revision bigint,
    p_codigo text, p_correlacion uuid)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_estado text;
BEGIN
    PERFORM comun.exigir_ap(p_actor);
    IF p_codigo NOT IN ('ENVIO_INICIAL', 'VERIFICACION_RUNTIME', 'INVITACIONES_DESHABILITADAS') THEN
        RAISE EXCEPTION 'Código de fallo inválido.' USING ERRCODE = '22023';
    END IF;
    SELECT e.estado INTO v_estado FROM comun.empresas e
    WHERE e.id = p_empresa AND e.revision_registro = p_revision
        AND e.estado IN ('EN_PREPARACION', 'ERROR_APROVISIONAMIENTO') FOR UPDATE;
    IF NOT FOUND THEN RETURN false; END IF;
    UPDATE comun.empresas SET estado = 'ERROR_APROVISIONAMIENTO', referencia_fallo = p_correlacion,
        revision_registro = revision_registro + 1 WHERE id = p_empresa;
    INSERT INTO comun.eventos_plataforma(empresa_id, actor_id, operacion, estado_anterior, estado_nuevo,
                                        motivo, correlacion_id)
    VALUES (p_empresa, p_actor, 'FALLO_ALTA', v_estado, 'ERROR_APROVISIONAMIENTO', p_codigo, p_correlacion);
    RETURN true;
END;
$$;

REVOKE ALL ON FUNCTION comun.exigir_ap(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION comun.empresas_de_plataforma(uuid, uuid, integer, bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION comun.reservar_empresa(uuid, uuid, uuid, text, text, text, text, text, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION comun.cambiar_disponibilidad_empresa(uuid, uuid, bigint, boolean, text, text, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION comun.activar_con_invitacion_inicial(uuid, uuid, bigint, uuid, bytea, integer, text, boolean, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION comun.registrar_fallo_de_alta(uuid, uuid, bigint, text, uuid) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION comun.empresas_de_plataforma(uuid, uuid, integer, bigint) TO "${runtime_role}";
GRANT EXECUTE ON FUNCTION comun.reservar_empresa(uuid, uuid, uuid, text, text, text, text, text, uuid) TO "${runtime_role}";
GRANT EXECUTE ON FUNCTION comun.cambiar_disponibilidad_empresa(uuid, uuid, bigint, boolean, text, text, uuid) TO "${runtime_role}";
GRANT EXECUTE ON FUNCTION comun.activar_con_invitacion_inicial(uuid, uuid, bigint, uuid, bytea, integer, text, boolean, uuid) TO "${runtime_role}";
GRANT EXECUTE ON FUNCTION comun.registrar_fallo_de_alta(uuid, uuid, bigint, text, uuid) TO "${runtime_role}";
