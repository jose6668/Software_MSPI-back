CREATE TABLE "${flyway:defaultSchema}".contexto_empresa (
    empresa_id uuid PRIMARY KEY REFERENCES comun.empresas(id),
    unica_fila boolean NOT NULL DEFAULT true UNIQUE CHECK (unica_fila),
    creado_en timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT contexto_empresa_propietaria CHECK (empresa_id = '${empresa_id}'::uuid)
);

INSERT INTO "${flyway:defaultSchema}".contexto_empresa (empresa_id) VALUES ('${empresa_id}'::uuid);

-- Runtime can read, but cannot replace or remove the owning company.
GRANT SELECT ON TABLE "${flyway:defaultSchema}".contexto_empresa TO "${runtime_role}";
