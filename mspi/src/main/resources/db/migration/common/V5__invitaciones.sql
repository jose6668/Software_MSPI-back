CREATE TABLE comun.invitaciones (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL REFERENCES comun.empresas(id),
    emisor_id uuid NOT NULL REFERENCES comun.usuarios(id),
    correo_destinatario varchar(254) NOT NULL,
    clave_destinatario varchar(254) NOT NULL,
    huella_token bytea NOT NULL UNIQUE CHECK (octet_length(huella_token) = 32),
    creada_en timestamptz NOT NULL,
    vence_en timestamptz NOT NULL CHECK (vence_en > creada_en),
    aceptada_en timestamptz,
    aceptada_por uuid REFERENCES comun.usuarios(id),
    revocada_en timestamptz,
    revocada_por uuid REFERENCES comun.usuarios(id),
    CHECK ((aceptada_en IS NULL) = (aceptada_por IS NULL)),
    CHECK ((revocada_en IS NULL) = (revocada_por IS NULL)),
    CHECK (aceptada_en IS NULL OR revocada_en IS NULL),
    CHECK (aceptada_en IS NULL OR (aceptada_en >= creada_en AND aceptada_en < vence_en)),
    CHECK (revocada_en IS NULL OR (revocada_en >= creada_en AND revocada_en < vence_en))
);

CREATE INDEX invitaciones_por_empresa ON comun.invitaciones (empresa_id, creada_en);

CREATE TABLE comun.invitacion_roles (
    invitacion_id uuid NOT NULL REFERENCES comun.invitaciones(id),
    rol_codigo text NOT NULL REFERENCES comun.roles_empresariales(codigo),
    PRIMARY KEY (invitacion_id, rol_codigo)
);

-- Private helper. Lock the state and authority until the mutation finishes.
CREATE FUNCTION comun.puede_gestionar_invitaciones(p_actor uuid, p_empresa uuid, p_version text)
RETURNS boolean LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM 1 FROM comun.empresas e
        JOIN comun.membresias m ON m.empresa_id = e.id
        JOIN comun.membresia_roles r ON r.membresia_id = m.id
        JOIN comun.usuarios u ON u.id = m.usuario_id
    WHERE e.id = p_empresa AND e.estado = 'ACTIVA' AND e.version_estructura = p_version
        AND u.id = p_actor AND u.estado_cuenta = 'HABILITADA'
        AND m.estado = 'ACTIVA' AND r.rol_codigo = 'AE'
    FOR SHARE OF e, m, r, u;
    RETURN FOUND;
END;
$$;

CREATE FUNCTION comun.emitir_invitacion(p_actor uuid, p_empresa uuid, p_id uuid,
    p_correo text, p_clave text, p_huella bytea, p_roles text[], p_segundos integer, p_version text)
RETURNS timestamptz LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_now timestamptz;
    v_vence timestamptz;
BEGIN
    IF NOT comun.puede_gestionar_invitaciones(p_actor, p_empresa, p_version) THEN
        RETURN NULL;
    END IF;
    IF p_segundos IS NULL OR p_segundos < 1 OR p_roles IS NULL OR cardinality(p_roles) NOT BETWEEN 1 AND 4
       OR (SELECT count(DISTINCT r.codigo) FROM comun.roles_empresariales r
           WHERE r.codigo = ANY (p_roles)) <> cardinality(p_roles) THEN
        RAISE EXCEPTION 'Parámetros de invitación inválidos.' USING ERRCODE = '22023';
    END IF;
    v_now := clock_timestamp();
    v_vence := v_now + make_interval(secs => p_segundos);
    INSERT INTO comun.invitaciones(id, empresa_id, emisor_id, correo_destinatario, clave_destinatario,
                                  huella_token, creada_en, vence_en)
    VALUES (p_id, p_empresa, p_actor, p_correo, p_clave, p_huella, v_now, v_vence);
    INSERT INTO comun.invitacion_roles(invitacion_id, rol_codigo)
    SELECT p_id, unnest(p_roles);
    RETURN v_vence;
END;
$$;

CREATE FUNCTION comun.revocar_invitacion(p_actor uuid, p_empresa uuid, p_id uuid, p_version text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_inv comun.invitaciones%ROWTYPE;
    v_now timestamptz;
BEGIN
    IF NOT comun.puede_gestionar_invitaciones(p_actor, p_empresa, p_version) THEN
        RETURN NULL;
    END IF;
    SELECT i.* INTO v_inv FROM comun.invitaciones i WHERE i.id = p_id AND i.empresa_id = p_empresa FOR UPDATE;
    v_now := clock_timestamp();
    IF NOT FOUND OR v_inv.aceptada_en IS NOT NULL OR v_inv.revocada_en IS NOT NULL OR v_inv.vence_en <= v_now THEN
        RETURN false;
    END IF;
    UPDATE comun.invitaciones SET revocada_en = v_now, revocada_por = p_actor WHERE id = p_id;
    RETURN true;
END;
$$;

-- Cheap precheck before password hashing. The acceptance function repeats all checks under locks.
CREATE FUNCTION comun.invitacion_admite_alta(p_huella bytea, p_version text)
RETURNS boolean LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1 FROM comun.invitaciones i
        JOIN comun.empresas e ON e.id = i.empresa_id
        WHERE i.huella_token = p_huella AND i.aceptada_en IS NULL AND i.revocada_en IS NULL
            AND i.vence_en > clock_timestamp() AND e.estado = 'ACTIVA' AND e.version_estructura = p_version
            AND NOT EXISTS (SELECT 1 FROM comun.usuarios u WHERE u.clave_correo = i.clave_destinatario)
    )
$$;

CREATE FUNCTION comun.aceptar_invitacion(p_actor uuid, p_nuevo_usuario uuid, p_nombre text, p_hash text,
    p_huella bytea, p_membresia uuid, p_version text)
RETURNS TABLE (empresa_id uuid, membresia_id uuid, usuario_id uuid, roles text[])
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_empresa uuid;
    v_inv comun.invitaciones%ROWTYPE;
    v_usuario uuid;
    v_roles text[];
    v_membresia uuid;
    v_now timestamptz;
BEGIN
    -- Same lock order as revocation: company, then invitation.
    SELECT i.empresa_id INTO v_empresa FROM comun.invitaciones i WHERE i.huella_token = p_huella;
    IF NOT FOUND THEN RETURN; END IF;

    PERFORM 1 FROM comun.empresas e WHERE e.id = v_empresa AND e.estado = 'ACTIVA'
        AND e.version_estructura = p_version FOR SHARE;
    IF NOT FOUND THEN RETURN; END IF;

    SELECT i.* INTO v_inv FROM comun.invitaciones i
    WHERE i.huella_token = p_huella AND i.empresa_id = v_empresa FOR UPDATE;
    IF NOT FOUND OR v_inv.aceptada_en IS NOT NULL OR v_inv.revocada_en IS NOT NULL
            OR v_inv.vence_en <= clock_timestamp() THEN RETURN; END IF;

    SELECT array_agg(r.rol_codigo ORDER BY r.rol_codigo) INTO v_roles
    FROM comun.invitacion_roles r WHERE r.invitacion_id = v_inv.id;
    IF v_roles IS NULL THEN RETURN; END IF;

    IF p_actor IS NULL THEN
        IF p_nuevo_usuario IS NULL OR p_nombre IS NULL OR p_hash IS NULL THEN RETURN; END IF;
        INSERT INTO comun.usuarios(id, nombre_visible, correo, clave_correo, correo_verificado_en,
                                   estado_cuenta, hash_credencial)
        VALUES (p_nuevo_usuario, p_nombre, v_inv.correo_destinatario, v_inv.clave_destinatario, clock_timestamp(),
                'HABILITADA', p_hash)
        ON CONFLICT (clave_correo) DO NOTHING
        RETURNING id INTO v_usuario;
    ELSE
        SELECT u.id INTO v_usuario FROM comun.usuarios u
        WHERE u.id = p_actor AND u.clave_correo = v_inv.clave_destinatario
            AND u.estado_cuenta = 'HABILITADA' AND u.correo_verificado_en IS NOT NULL
        FOR SHARE;
    END IF;
    IF v_usuario IS NULL THEN RETURN; END IF;

    INSERT INTO comun.membresias(id, empresa_id, usuario_id, estado, rol_ancla)
    VALUES (p_membresia, v_empresa, v_usuario, 'ACTIVA', v_roles[1])
    ON CONFLICT DO NOTHING RETURNING id INTO v_membresia;
    IF NOT FOUND THEN
        IF p_actor IS NULL THEN
            RAISE EXCEPTION 'No se pudo incorporar la cuenta invitada.' USING ERRCODE = '23505';
        END IF;
        RETURN;
    END IF;

    INSERT INTO comun.membresia_roles(membresia_id, rol_codigo)
    SELECT v_membresia, unnest(v_roles);
    v_now := clock_timestamp();
    IF v_now >= v_inv.vence_en THEN
        -- Roll back the new membership/account if a lock wait crossed the deadline.
        RAISE EXCEPTION 'Invitación no disponible.' USING ERRCODE = 'P0901';
    END IF;
    UPDATE comun.invitaciones SET aceptada_en = v_now, aceptada_por = v_usuario WHERE id = v_inv.id;

    RETURN QUERY SELECT v_empresa, v_membresia, v_usuario, v_roles;
END;
$$;

REVOKE ALL ON FUNCTION comun.puede_gestionar_invitaciones(uuid, uuid, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION comun.emitir_invitacion(uuid, uuid, uuid, text, text, bytea, text[], integer, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION comun.revocar_invitacion(uuid, uuid, uuid, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION comun.invitacion_admite_alta(bytea, text) FROM PUBLIC;
REVOKE ALL ON FUNCTION comun.aceptar_invitacion(uuid, uuid, text, text, bytea, uuid, text) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION comun.emitir_invitacion(uuid, uuid, uuid, text, text, bytea, text[], integer, text)
    TO "${runtime_role}";
GRANT EXECUTE ON FUNCTION comun.revocar_invitacion(uuid, uuid, uuid, text) TO "${runtime_role}";
GRANT EXECUTE ON FUNCTION comun.invitacion_admite_alta(bytea, text) TO "${runtime_role}";
GRANT EXECUTE ON FUNCTION comun.aceptar_invitacion(uuid, uuid, text, text, bytea, uuid, text) TO "${runtime_role}";
