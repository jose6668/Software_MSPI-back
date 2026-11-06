CREATE TABLE "${flyway:defaultSchema}".empresas (
    id uuid PRIMARY KEY,
    nombre text NOT NULL CHECK (length(btrim(nombre)) > 0),
    zona_horaria text NOT NULL CHECK (length(btrim(zona_horaria)) > 0),
    esquema_interno text NOT NULL UNIQUE,
    estado text NOT NULL DEFAULT 'EN_PREPARACION'
        CHECK (estado IN ('EN_PREPARACION', 'ACTIVA', 'SUSPENDIDA', 'ERROR_APROVISIONAMIENTO')),
    version_estructura text,
    revision_registro bigint NOT NULL DEFAULT 1 CHECK (revision_registro > 0),
    creado_en timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT empresa_esquema_derivado
        CHECK (esquema_interno = 'emp_' || replace(id::text, '-', '')),
    CONSTRAINT empresa_activa_versionada
        CHECK (estado <> 'ACTIVA' OR nullif(btrim(version_estructura), '') IS NOT NULL)
);

GRANT SELECT ON TABLE "${flyway:defaultSchema}".empresas TO "${runtime_role}";
