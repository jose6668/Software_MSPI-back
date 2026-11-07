CREATE TABLE "${flyway:defaultSchema}".activos (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL REFERENCES "${flyway:defaultSchema}".contexto_empresa(empresa_id),
    codigo varchar(80) NOT NULL CHECK (btrim(codigo) <> ''),
    codigo_normalizado text COLLATE "C" NOT NULL CHECK (btrim(codigo_normalizado) <> ''),
    nombre varchar(250) NOT NULL CHECK (btrim(nombre) <> ''),
    descripcion text NOT NULL CHECK (btrim(descripcion) <> ''),
    tipo text NOT NULL CHECK (tipo IN ('INFORMACION', 'HARDWARE', 'SOFTWARE', 'SERVICIO',
                                      'RECURSO_HUMANO', 'INSTALACION', 'RED')),
    proceso_id uuid NOT NULL,
    propietario_id uuid NOT NULL,
    custodio_id uuid,
    ubicacion varchar(1000),
    estado text NOT NULL CHECK (estado IN ('ACTIVO', 'ARCHIVADO')),
    revision bigint NOT NULL CHECK (revision >= 0),
    UNIQUE (empresa_id, id),
    CONSTRAINT activo_codigo_local UNIQUE (empresa_id, codigo_normalizado),
    CONSTRAINT activo_proceso_local FOREIGN KEY (empresa_id, proceso_id)
        REFERENCES "${flyway:defaultSchema}".procesos(empresa_id, id),
    CONSTRAINT activo_propietario_local FOREIGN KEY (empresa_id, propietario_id)
        REFERENCES "${flyway:defaultSchema}".responsables_negocio(empresa_id, id),
    CONSTRAINT activo_custodio_local FOREIGN KEY (empresa_id, custodio_id)
        REFERENCES "${flyway:defaultSchema}".responsables_negocio(empresa_id, id)
);
CREATE INDEX activos_por_proceso ON "${flyway:defaultSchema}".activos (empresa_id, proceso_id);
CREATE INDEX activos_por_propietario ON "${flyway:defaultSchema}".activos (empresa_id, propietario_id);
CREATE INDEX activos_por_custodio ON "${flyway:defaultSchema}".activos (empresa_id, custodio_id);

ALTER TABLE "${flyway:defaultSchema}".eventos_actividad
    ADD COLUMN campos_modificados text[] NOT NULL DEFAULT ARRAY[]::text[],
    ADD COLUMN cambios_referencias jsonb;
CREATE INDEX historial_por_activo ON "${flyway:defaultSchema}".eventos_actividad
    (empresa_id, objeto_id, creado_en DESC, id DESC) WHERE tipo_objeto = 'ACTIVO';

CREATE FUNCTION "${flyway:defaultSchema}".historial_activo(p_empresa uuid, p_activo uuid, p_limite integer, p_offset bigint)
RETURNS TABLE (actor_id uuid, accion text, estado_anterior text, estado_nuevo text, motivo text,
               correlacion_id uuid, creado_en timestamptz, campos_modificados text[], cambios_referencias text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
    SELECT e.actor_id, e.accion, e.estado_anterior, e.estado_nuevo, e.motivo::text,
           e.correlacion_id, e.creado_en, e.campos_modificados, e.cambios_referencias::text
    FROM "${flyway:defaultSchema}".eventos_actividad e
    WHERE e.empresa_id = p_empresa AND e.objeto_id = p_activo AND e.tipo_objeto = 'ACTIVO'
    ORDER BY e.creado_en DESC, e.id DESC LIMIT p_limite OFFSET p_offset;
$$;

REVOKE ALL ON FUNCTION "${flyway:defaultSchema}".historial_activo(uuid, uuid, integer, bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION "${flyway:defaultSchema}".historial_activo(uuid, uuid, integer, bigint) TO "${runtime_role}";
GRANT SELECT, INSERT ON "${flyway:defaultSchema}".activos TO "${runtime_role}";
GRANT UPDATE (nombre, descripcion, tipo, proceso_id, propietario_id, custodio_id, ubicacion, estado, revision)
    ON "${flyway:defaultSchema}".activos TO "${runtime_role}";
