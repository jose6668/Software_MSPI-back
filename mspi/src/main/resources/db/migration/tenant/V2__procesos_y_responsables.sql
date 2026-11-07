CREATE TABLE "${flyway:defaultSchema}".responsables_negocio (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL REFERENCES "${flyway:defaultSchema}".contexto_empresa(empresa_id),
    nombre varchar(200) NOT NULL CHECK (btrim(nombre) <> ''),
    tipo text NOT NULL CHECK (tipo IN ('PERSONA', 'CARGO', 'AREA')),
    membresia_id uuid,
    estado text NOT NULL CHECK (estado IN ('ACTIVO', 'ARCHIVADO')),
    revision bigint NOT NULL CHECK (revision >= 0),
    UNIQUE (empresa_id, id),
    CONSTRAINT responsable_membresia_local FOREIGN KEY (empresa_id, membresia_id)
        REFERENCES comun.membresias(empresa_id, id)
);

CREATE TABLE "${flyway:defaultSchema}".procesos (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL REFERENCES "${flyway:defaultSchema}".contexto_empresa(empresa_id),
    nombre varchar(200) NOT NULL CHECK (btrim(nombre) <> ''),
    descripcion text,
    responsable_id uuid,
    estado text NOT NULL CHECK (estado IN ('ACTIVO', 'ARCHIVADO')),
    revision bigint NOT NULL CHECK (revision >= 0),
    UNIQUE (empresa_id, id),
    FOREIGN KEY (empresa_id, responsable_id)
        REFERENCES "${flyway:defaultSchema}".responsables_negocio(empresa_id, id)
);

CREATE INDEX procesos_por_responsable
    ON "${flyway:defaultSchema}".procesos (empresa_id, responsable_id, nombre, id);
CREATE INDEX procesos_por_nombre ON "${flyway:defaultSchema}".procesos (empresa_id, nombre, id);
CREATE INDEX responsables_por_nombre ON "${flyway:defaultSchema}".responsables_negocio (empresa_id, nombre, id);

CREATE TABLE "${flyway:defaultSchema}".eventos_actividad (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL REFERENCES "${flyway:defaultSchema}".contexto_empresa(empresa_id),
    actor_id uuid NOT NULL,
    accion text NOT NULL,
    tipo_objeto text NOT NULL,
    objeto_id uuid NOT NULL,
    resultado text NOT NULL DEFAULT 'EXITO',
    estado_anterior text,
    estado_nuevo text,
    motivo varchar(2000),
    correlacion_id uuid NOT NULL,
    creado_en timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX eventos_actividad_por_fecha
    ON "${flyway:defaultSchema}".eventos_actividad (empresa_id, creado_en, id);

GRANT SELECT, INSERT, UPDATE ON "${flyway:defaultSchema}".procesos,
    "${flyway:defaultSchema}".responsables_negocio TO "${runtime_role}";
GRANT INSERT ON "${flyway:defaultSchema}".eventos_actividad TO "${runtime_role}";
