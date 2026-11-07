CREATE TABLE "${flyway:defaultSchema}".usuarios (
    id uuid PRIMARY KEY,
    nombre_visible varchar(150) NOT NULL CHECK (btrim(nombre_visible) <> ''),
    correo varchar(254) NOT NULL CHECK (btrim(correo) <> ''),
    clave_correo varchar(254) NOT NULL UNIQUE CHECK (btrim(clave_correo) <> ''),
    correo_verificado_en timestamptz,
    estado_cuenta text NOT NULL CHECK (estado_cuenta IN ('PENDIENTE_VERIFICACION', 'HABILITADA', 'DESHABILITADA')),
    hash_credencial text,
    CHECK (hash_credencial IS NULL OR hash_credencial ~ '^[{]pbkdf2-sha256-v1[}][0-9a-f]{96}$'),
    CHECK (estado_cuenta <> 'HABILITADA' OR (correo_verificado_en IS NOT NULL AND hash_credencial IS NOT NULL))
);

GRANT SELECT (id, nombre_visible, clave_correo, estado_cuenta, hash_credencial)
    ON "${flyway:defaultSchema}".usuarios TO "${runtime_role}";
