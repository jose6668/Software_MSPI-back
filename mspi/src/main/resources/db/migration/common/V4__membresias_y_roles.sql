CREATE TABLE comun.roles_empresariales (
    codigo text PRIMARY KEY CHECK (codigo IN ('AE', 'GS', 'RA', 'AU'))
);

INSERT INTO comun.roles_empresariales (codigo) VALUES ('AE'), ('GS'), ('RA'), ('AU');

CREATE TABLE comun.membresias (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL REFERENCES comun.empresas(id),
    usuario_id uuid NOT NULL REFERENCES comun.usuarios(id),
    estado text NOT NULL CHECK (estado IN ('ACTIVA', 'REVOCADA')),
    revocada_en timestamptz,
    rol_ancla text,
    UNIQUE (empresa_id, usuario_id),
    UNIQUE (empresa_id, id),
    CHECK ((estado = 'REVOCADA') = (revocada_en IS NOT NULL)),
    CHECK (estado <> 'ACTIVA' OR rol_ancla IS NOT NULL)
);

CREATE INDEX membresias_por_usuario ON comun.membresias (usuario_id);

CREATE TABLE comun.membresia_roles (
    membresia_id uuid NOT NULL REFERENCES comun.membresias(id),
    rol_codigo text NOT NULL REFERENCES comun.roles_empresariales(codigo),
    PRIMARY KEY (membresia_id, rol_codigo)
);

-- An internal witness of a nonempty role set, with no authorization semantics.
ALTER TABLE comun.membresias ADD CONSTRAINT membresia_rol_presente
    FOREIGN KEY (id, rol_ancla) REFERENCES comun.membresia_roles (membresia_id, rol_codigo)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE comun.atribuciones_plataforma (
    usuario_id uuid NOT NULL REFERENCES comun.usuarios(id),
    codigo text NOT NULL CHECK (codigo = 'AP'),
    PRIMARY KEY (usuario_id, codigo)
);

GRANT SELECT (id, empresa_id, usuario_id, estado) ON comun.membresias TO "${runtime_role}";
GRANT SELECT ON comun.membresia_roles TO "${runtime_role}";
