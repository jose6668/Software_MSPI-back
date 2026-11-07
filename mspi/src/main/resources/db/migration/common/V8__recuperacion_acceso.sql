ALTER TABLE comun.usuarios ADD COLUMN version_credencial bigint NOT NULL DEFAULT 0 CHECK (version_credencial >= 0);
GRANT SELECT (version_credencial) ON comun.usuarios TO "${runtime_role}";

CREATE TABLE comun.comprobantes_recuperacion (
    huella bytea PRIMARY KEY CHECK (octet_length(huella) = 32),
    usuario_id uuid NOT NULL REFERENCES comun.usuarios(id),
    clave_canal varchar(254) NOT NULL,
    canal_verificado_en timestamptz NOT NULL,
    version_credencial bigint NOT NULL CHECK (version_credencial >= 0),
    emitido_en timestamptz NOT NULL,
    vence_en timestamptz NOT NULL CHECK (vence_en > emitido_en),
    consumido_en timestamptz,
    invalidado_en timestamptz,
    CHECK (consumido_en IS NULL OR consumido_en >= emitido_en AND consumido_en < vence_en),
    CHECK (invalidado_en IS NULL OR invalidado_en >= emitido_en),
    CHECK (consumido_en IS NULL OR invalidado_en IS NULL)
);
CREATE INDEX comprobantes_por_usuario_vigentes ON comun.comprobantes_recuperacion(usuario_id)
    WHERE consumido_en IS NULL AND invalidado_en IS NULL;

CREATE FUNCTION comun.emitir_recuperacion(p_clave text, p_huella bytea, p_segundos integer)
RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_usuario comun.usuarios%ROWTYPE;
    v_ahora timestamptz;
BEGIN
    SELECT u.* INTO v_usuario FROM comun.usuarios u
    WHERE u.clave_correo = p_clave AND u.estado_cuenta = 'HABILITADA' AND u.correo_verificado_en IS NOT NULL
    FOR UPDATE;
    IF NOT FOUND THEN RETURN NULL; END IF;

    v_ahora := clock_timestamp();
    INSERT INTO comun.comprobantes_recuperacion(huella, usuario_id, clave_canal, canal_verificado_en,
        version_credencial, emitido_en, vence_en)
    VALUES (p_huella, v_usuario.id, v_usuario.clave_correo, v_usuario.correo_verificado_en,
        v_usuario.version_credencial, v_ahora, v_ahora + make_interval(secs => p_segundos));
    RETURN v_usuario.correo;
END;
$$;

-- Shared validity conditions for the cheap precheck and the locked confirmation.
CREATE FUNCTION comun.recuperacion_vigente(p_huella bytea, p_ahora timestamptz)
RETURNS boolean LANGUAGE sql STABLE SET search_path = pg_catalog, pg_temp AS $$
    SELECT EXISTS (
        SELECT 1 FROM comun.comprobantes_recuperacion c JOIN comun.usuarios u ON u.id = c.usuario_id
        WHERE c.huella = p_huella AND c.consumido_en IS NULL AND c.invalidado_en IS NULL AND c.vence_en > p_ahora
          AND u.estado_cuenta = 'HABILITADA' AND u.version_credencial = c.version_credencial
          AND u.clave_correo = c.clave_canal AND u.correo_verificado_en = c.canal_verificado_en
    );
$$;

CREATE FUNCTION comun.puede_confirmar_recuperacion(p_huella bytea)
RETURNS boolean LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    SELECT comun.recuperacion_vigente(p_huella, clock_timestamp());
$$;

CREATE FUNCTION comun.confirmar_recuperacion(p_huella bytea, p_hash text)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_usuario uuid;
    v_ahora timestamptz;
BEGIN
    -- The account is the serialization point for issuance and consumption.
    SELECT u.id INTO v_usuario
    FROM comun.usuarios u JOIN comun.comprobantes_recuperacion c ON c.usuario_id = u.id
    WHERE c.huella = p_huella FOR UPDATE OF u;
    IF NOT FOUND THEN RETURN false; END IF;

    v_ahora := clock_timestamp();
    IF NOT comun.recuperacion_vigente(p_huella, v_ahora) THEN RETURN false; END IF;

    UPDATE comun.usuarios SET hash_credencial = p_hash, version_credencial = version_credencial + 1
    WHERE id = v_usuario;
    UPDATE comun.comprobantes_recuperacion SET consumido_en = v_ahora WHERE huella = p_huella;
    UPDATE comun.comprobantes_recuperacion SET invalidado_en = v_ahora
    WHERE usuario_id = v_usuario AND consumido_en IS NULL AND invalidado_en IS NULL AND vence_en > v_ahora;
    RETURN true;
END;
$$;

REVOKE ALL ON FUNCTION comun.recuperacion_vigente(bytea, timestamptz) FROM PUBLIC;
REVOKE ALL ON FUNCTION comun.emitir_recuperacion(text, bytea, integer),
    comun.puede_confirmar_recuperacion(bytea), comun.confirmar_recuperacion(bytea, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION comun.emitir_recuperacion(text, bytea, integer),
    comun.puede_confirmar_recuperacion(bytea), comun.confirmar_recuperacion(bytea, text) TO "${runtime_role}";
