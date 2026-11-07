ALTER TABLE comun.membresias ADD COLUMN revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0);

CREATE TABLE comun.eventos_administracion_empresa (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    empresa_id uuid NOT NULL REFERENCES comun.empresas(id),
    membresia_id uuid,
    actor_id uuid NOT NULL REFERENCES comun.usuarios(id),
    operacion text NOT NULL CHECK (operacion IN ('CONFIGURAR', 'CAMBIAR_ROLES', 'REVOCAR_MEMBRESIA')),
    anterior jsonb NOT NULL,
    nuevo jsonb NOT NULL,
    motivo varchar(1000),
    correlacion_id uuid NOT NULL,
    registrado_en timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (empresa_id, membresia_id) REFERENCES comun.membresias (empresa_id, id),
    CHECK ((operacion = 'CONFIGURAR' AND membresia_id IS NULL AND motivo IS NULL)
        OR (operacion <> 'CONFIGURAR' AND membresia_id IS NOT NULL AND btrim(motivo) <> '' AND motivo IS NOT NULL))
);
CREATE INDEX eventos_administracion_por_empresa ON comun.eventos_administracion_empresa (empresa_id, id);

-- Invitations and administration take the company lock before membership or invitation locks.
CREATE OR REPLACE FUNCTION comun.puede_gestionar_invitaciones(p_actor uuid, p_empresa uuid, p_version text)
RETURNS boolean LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM 1 FROM comun.empresas e
    WHERE e.id = p_empresa AND e.estado = 'ACTIVA' AND e.version_estructura = p_version
    FOR SHARE;
    IF NOT FOUND THEN RETURN false; END IF;

    PERFORM 1 FROM comun.membresias m
    JOIN comun.membresia_roles r ON r.membresia_id = m.id
    JOIN comun.usuarios u ON u.id = m.usuario_id
    WHERE m.empresa_id = p_empresa AND u.id = p_actor AND u.estado_cuenta = 'HABILITADA'
      AND m.estado = 'ACTIVA' AND r.rol_codigo = 'AE'
    FOR SHARE OF m, r, u;
    RETURN FOUND;
END;
$$;

CREATE FUNCTION comun.exigir_rol_empresarial(p_actor uuid, p_empresa uuid, p_version text, p_roles text[])
RETURNS void LANGUAGE plpgsql STABLE SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM comun.membresias m
        JOIN comun.membresia_roles r ON r.membresia_id = m.id
        JOIN comun.usuarios u ON u.id = m.usuario_id
        JOIN comun.empresas e ON e.id = m.empresa_id
        WHERE e.id = p_empresa AND e.estado = 'ACTIVA' AND e.version_estructura = p_version
          AND u.id = p_actor AND u.estado_cuenta = 'HABILITADA'
          AND m.estado = 'ACTIVA' AND r.rol_codigo = ANY(p_roles)
    ) THEN
        RAISE EXCEPTION 'Acceso empresarial no autorizado.' USING ERRCODE = 'P1101';
    END IF;
END;
$$;

CREATE FUNCTION comun.configuracion_empresa(p_actor uuid, p_empresa uuid, p_version text)
RETURNS TABLE (nombre text, descripcion text, zona_horaria text, revision bigint)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM comun.exigir_rol_empresarial(p_actor, p_empresa, p_version, ARRAY['AE', 'GS', 'AU']);
    RETURN QUERY SELECT e.nombre::text, e.descripcion, e.zona_horaria::text, e.revision_registro
    FROM comun.empresas e WHERE e.id = p_empresa;
END;
$$;

CREATE FUNCTION comun.membresias_empresa(p_actor uuid, p_empresa uuid, p_version text, p_limit integer, p_offset bigint)
RETURNS TABLE (id uuid, nombre text, correo text, roles text[], estado text, revocada_en timestamptz, revision bigint)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM comun.exigir_rol_empresarial(p_actor, p_empresa, p_version, ARRAY['AE']);
    RETURN QUERY
    SELECT m.id, u.nombre_visible::text, u.correo::text,
           ARRAY(SELECT r.rol_codigo FROM comun.membresia_roles r WHERE r.membresia_id = m.id ORDER BY r.rol_codigo),
           m.estado, m.revocada_en, m.revision
    FROM comun.membresias m JOIN comun.usuarios u ON u.id = m.usuario_id
    WHERE m.empresa_id = p_empresa
    ORDER BY u.nombre_visible, m.id LIMIT p_limit OFFSET p_offset;
END;
$$;

CREATE FUNCTION comun.responsables_empresa(p_actor uuid, p_empresa uuid, p_version text, p_limit integer, p_offset bigint)
RETURNS TABLE (id uuid, nombre text)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM comun.exigir_rol_empresarial(p_actor, p_empresa, p_version, ARRAY['AE', 'GS', 'AU']);
    RETURN QUERY
    SELECT m.id, u.nombre_visible::text
    FROM comun.membresias m JOIN comun.usuarios u ON u.id = m.usuario_id
    WHERE m.empresa_id = p_empresa AND m.estado = 'ACTIVA' AND u.estado_cuenta = 'HABILITADA'
      AND EXISTS (SELECT 1 FROM comun.membresia_roles r WHERE r.membresia_id = m.id
                  AND r.rol_codigo IN ('AE', 'GS', 'RA'))
    ORDER BY u.nombre_visible, m.id LIMIT p_limit OFFSET p_offset;
END;
$$;

CREATE FUNCTION comun.invitaciones_empresa(p_actor uuid, p_empresa uuid, p_version text, p_limit integer, p_offset bigint)
RETURNS TABLE (id uuid, correo text, roles text[], estado text, creada_en timestamptz, vence_en timestamptz,
               aceptada_en timestamptz, revocada_en timestamptz)
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM comun.exigir_rol_empresarial(p_actor, p_empresa, p_version, ARRAY['AE']);
    RETURN QUERY
    SELECT i.id, i.correo_destinatario::text,
           ARRAY(SELECT r.rol_codigo FROM comun.invitacion_roles r WHERE r.invitacion_id = i.id ORDER BY r.rol_codigo),
           CASE WHEN i.aceptada_en IS NOT NULL THEN 'ACEPTADA'
                WHEN i.revocada_en IS NOT NULL THEN 'REVOCADA'
                WHEN i.vence_en <= statement_timestamp() THEN 'VENCIDA'
                ELSE 'PENDIENTE' END,
           i.creada_en, i.vence_en, i.aceptada_en, i.revocada_en
    FROM comun.invitaciones i WHERE i.empresa_id = p_empresa
    ORDER BY i.creada_en DESC, i.id LIMIT p_limit OFFSET p_offset;
END;
$$;

CREATE FUNCTION comun.configurar_empresa(p_actor uuid, p_empresa uuid, p_version text, p_revision bigint,
    p_nombre text, p_descripcion text, p_zona text, p_correlacion uuid)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_empresa comun.empresas%ROWTYPE;
BEGIN
    PERFORM comun.exigir_rol_empresarial(p_actor, p_empresa, p_version, ARRAY['AE']);
    SELECT e.* INTO v_empresa FROM comun.empresas e WHERE e.id = p_empresa FOR UPDATE;
    IF NOT comun.puede_gestionar_invitaciones(p_actor, p_empresa, p_version) THEN
        RAISE EXCEPTION 'Acceso empresarial no autorizado.' USING ERRCODE = 'P1101';
    END IF;
    IF p_revision IS DISTINCT FROM v_empresa.revision_registro THEN
        RAISE EXCEPTION 'Revisión desactualizada.' USING ERRCODE = 'P1102';
    END IF;
    IF (p_nombre, p_descripcion, p_zona) IS NOT DISTINCT FROM
       (v_empresa.nombre, v_empresa.descripcion, v_empresa.zona_horaria) THEN
        RETURN false;
    END IF;
    UPDATE comun.empresas SET nombre = p_nombre, descripcion = p_descripcion,
        zona_horaria = p_zona, revision_registro = revision_registro + 1 WHERE id = p_empresa;
    INSERT INTO comun.eventos_administracion_empresa(empresa_id, actor_id, operacion, anterior, nuevo, correlacion_id)
    VALUES (p_empresa, p_actor, 'CONFIGURAR',
        jsonb_build_object('nombre', v_empresa.nombre, 'descripcion', v_empresa.descripcion,
                           'zonaHoraria', v_empresa.zona_horaria, 'revision', v_empresa.revision_registro),
        jsonb_build_object('nombre', p_nombre, 'descripcion', p_descripcion,
                           'zonaHoraria', p_zona, 'revision', v_empresa.revision_registro + 1), p_correlacion);
    RETURN true;
END;
$$;

CREATE FUNCTION comun.cambiar_membresia(p_actor uuid, p_empresa uuid, p_version text, p_id uuid, p_revision bigint,
    p_roles text[], p_revocar boolean, p_motivo text, p_correlacion uuid)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_membresia comun.membresias%ROWTYPE;
    v_anteriores text[];
    v_nuevos text[];
BEGIN
    PERFORM comun.exigir_rol_empresarial(p_actor, p_empresa, p_version, ARRAY['AE']);
    PERFORM 1 FROM comun.empresas e WHERE e.id = p_empresa FOR UPDATE;
    IF NOT comun.puede_gestionar_invitaciones(p_actor, p_empresa, p_version) THEN
        RAISE EXCEPTION 'Acceso empresarial no autorizado.' USING ERRCODE = 'P1101';
    END IF;

    SELECT m.* INTO v_membresia FROM comun.membresias m WHERE m.id = p_id AND m.empresa_id = p_empresa FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Membresía no disponible.' USING ERRCODE = 'P1103';
    END IF;
    IF p_revision IS DISTINCT FROM v_membresia.revision THEN
        RAISE EXCEPTION 'Revisión desactualizada.' USING ERRCODE = 'P1102';
    END IF;
    IF v_membresia.estado <> 'ACTIVA' THEN
        RAISE EXCEPTION 'Membresía revocada.' USING ERRCODE = 'P1105';
    END IF;
    IF p_motivo IS NULL OR btrim(p_motivo) = '' OR length(p_motivo) > 1000 OR p_revocar IS NULL THEN
        RAISE EXCEPTION 'Cambio de membresía inválido.' USING ERRCODE = '22023';
    END IF;

    SELECT ARRAY(SELECT r.rol_codigo FROM comun.membresia_roles r
                 WHERE r.membresia_id = p_id ORDER BY r.rol_codigo) INTO v_anteriores;
    IF p_revocar THEN
        v_nuevos := ARRAY[]::text[];
    ELSE
        SELECT ARRAY(SELECT r.codigo FROM comun.roles_empresariales r
                     WHERE r.codigo = ANY(p_roles) ORDER BY r.codigo) INTO v_nuevos;
        IF p_roles IS NULL OR cardinality(v_nuevos) = 0 OR cardinality(v_nuevos) <> cardinality(p_roles) THEN
            RAISE EXCEPTION 'Conjunto empresarial inválido.' USING ERRCODE = '22023';
        END IF;
        IF v_nuevos = v_anteriores THEN RETURN false; END IF;
    END IF;

    IF 'AE' = ANY(v_anteriores) AND NOT ('AE' = ANY(v_nuevos)) AND NOT EXISTS (
        SELECT 1 FROM comun.membresias m JOIN comun.membresia_roles r ON r.membresia_id = m.id
        WHERE m.empresa_id = p_empresa AND m.estado = 'ACTIVA' AND m.id <> p_id AND r.rol_codigo = 'AE'
    ) THEN
        RAISE EXCEPTION 'La empresa debe conservar un AE activo.' USING ERRCODE = 'P1104';
    END IF;

    IF p_revocar THEN
        UPDATE comun.membresias SET estado = 'REVOCADA', revocada_en = clock_timestamp(),
            revision = revision + 1 WHERE id = p_id;
    ELSE
        INSERT INTO comun.membresia_roles (membresia_id, rol_codigo)
        SELECT p_id, unnest(v_nuevos) ON CONFLICT DO NOTHING;
        UPDATE comun.membresias SET rol_ancla = v_nuevos[1], revision = revision + 1 WHERE id = p_id;
        DELETE FROM comun.membresia_roles r WHERE r.membresia_id = p_id AND NOT (r.rol_codigo = ANY(v_nuevos));
    END IF;
    INSERT INTO comun.eventos_administracion_empresa(empresa_id, membresia_id, actor_id, operacion,
        anterior, nuevo, motivo, correlacion_id)
    VALUES (p_empresa, p_id, p_actor, CASE WHEN p_revocar THEN 'REVOCAR_MEMBRESIA' ELSE 'CAMBIAR_ROLES' END,
        jsonb_build_object('estado', 'ACTIVA', 'roles', v_anteriores, 'revision', v_membresia.revision),
        jsonb_build_object('estado', CASE WHEN p_revocar THEN 'REVOCADA' ELSE 'ACTIVA' END,
                           'roles', v_nuevos, 'revision', v_membresia.revision + 1),
        p_motivo, p_correlacion);
    RETURN true;
END;
$$;

REVOKE ALL ON FUNCTION comun.exigir_rol_empresarial(uuid, uuid, text, text[]) FROM PUBLIC;
REVOKE ALL ON FUNCTION comun.configuracion_empresa(uuid, uuid, text),
    comun.membresias_empresa(uuid, uuid, text, integer, bigint),
    comun.responsables_empresa(uuid, uuid, text, integer, bigint),
    comun.invitaciones_empresa(uuid, uuid, text, integer, bigint),
    comun.configurar_empresa(uuid, uuid, text, bigint, text, text, text, uuid),
    comun.cambiar_membresia(uuid, uuid, text, uuid, bigint, text[], boolean, text, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION comun.configuracion_empresa(uuid, uuid, text),
    comun.membresias_empresa(uuid, uuid, text, integer, bigint),
    comun.responsables_empresa(uuid, uuid, text, integer, bigint),
    comun.invitaciones_empresa(uuid, uuid, text, integer, bigint),
    comun.configurar_empresa(uuid, uuid, text, bigint, text, text, text, uuid),
    comun.cambiar_membresia(uuid, uuid, text, uuid, bigint, text[], boolean, text, uuid) TO "${runtime_role}";
