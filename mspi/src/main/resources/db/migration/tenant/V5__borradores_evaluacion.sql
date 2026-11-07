CREATE TABLE "${flyway:defaultSchema}".evaluaciones (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL REFERENCES "${flyway:defaultSchema}".contexto_empresa(empresa_id),
    nombre varchar(200) NOT NULL CHECK (btrim(nombre) <> ''),
    fecha_evaluacion date NOT NULL,
    alcance text NOT NULL CHECK (btrim(alcance) <> ''),
    responsable_id uuid NOT NULL,
    version_catalogo_id uuid NOT NULL CONSTRAINT evaluacion_catalogo REFERENCES comun.versiones_catalogo(id),
    version_metodologia_id uuid NOT NULL CONSTRAINT evaluacion_metodologia REFERENCES comun.versiones_metodologia(id),
    estado text NOT NULL DEFAULT 'BORRADOR' CHECK (estado IN ('BORRADOR', 'EN_REVISION', 'CERRADA')),
    revision bigint NOT NULL CHECK (revision >= 0),
    creada_por uuid NOT NULL,
    creada_en timestamptz NOT NULL,
    actualizada_en timestamptz NOT NULL,
    CONSTRAINT evaluacion_responsable_local FOREIGN KEY (empresa_id, responsable_id)
        REFERENCES comun.membresias(empresa_id, id),
    UNIQUE (empresa_id, id),
    UNIQUE (empresa_id, id, version_catalogo_id)
);
CREATE INDEX evaluaciones_por_fecha ON "${flyway:defaultSchema}".evaluaciones (empresa_id, fecha_evaluacion DESC, id);

CREATE TABLE "${flyway:defaultSchema}".respuestas_evaluacion (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL REFERENCES "${flyway:defaultSchema}".contexto_empresa(empresa_id),
    evaluacion_id uuid NOT NULL,
    version_catalogo_id uuid NOT NULL,
    elemento_id uuid NOT NULL,
    tipo text NOT NULL CHECK (tipo IN ('CONTROL', 'CLAUSULA')),
    codigo varchar(80) NOT NULL,
    titulo varchar(250) NOT NULL,
    grupo varchar(80) NOT NULL,
    aplicabilidad text NOT NULL DEFAULT 'SIN_DECIDIR' CHECK (aplicabilidad IN ('SIN_DECIDIR', 'APLICA', 'NO_APLICA')),
    justificacion varchar(2000),
    calificacion integer CHECK (calificacion IN (0, 20, 40, 60, 80, 100)),
    fundamento text,
    actualizada_por uuid,
    actualizada_en timestamptz,
    FOREIGN KEY (empresa_id, evaluacion_id, version_catalogo_id)
        REFERENCES "${flyway:defaultSchema}".evaluaciones(empresa_id, id, version_catalogo_id),
    FOREIGN KEY (version_catalogo_id, elemento_id) REFERENCES comun.elementos_catalogo(version_catalogo_id, id),
    UNIQUE (empresa_id, evaluacion_id, id),
    UNIQUE (evaluacion_id, elemento_id),
    CHECK (aplicabilidad = 'APLICA' OR calificacion IS NULL),
    CHECK ((aplicabilidad = 'NO_APLICA') = (justificacion IS NOT NULL)),
    CHECK (justificacion IS NULL OR (tipo = 'CONTROL' AND btrim(justificacion) <> '')),
    CHECK (calificacion IS NULL OR (fundamento IS NOT NULL AND btrim(fundamento) <> '')),
    CHECK ((actualizada_por IS NULL) = (actualizada_en IS NULL))
);
CREATE INDEX respuestas_por_evaluacion ON "${flyway:defaultSchema}".respuestas_evaluacion
    (empresa_id, evaluacion_id, tipo, grupo, codigo, id);

CREATE TABLE "${flyway:defaultSchema}".hallazgos_evaluacion (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL REFERENCES "${flyway:defaultSchema}".contexto_empresa(empresa_id),
    evaluacion_id uuid NOT NULL,
    respuesta_id uuid NOT NULL,
    descripcion text NOT NULL CHECK (btrim(descripcion) <> ''),
    recomendacion text,
    creada_por uuid NOT NULL,
    creada_en timestamptz NOT NULL,
    actualizada_por uuid NOT NULL,
    actualizada_en timestamptz NOT NULL,
    FOREIGN KEY (empresa_id, evaluacion_id, respuesta_id)
        REFERENCES "${flyway:defaultSchema}".respuestas_evaluacion(empresa_id, evaluacion_id, id)
);
CREATE INDEX hallazgos_por_respuesta ON "${flyway:defaultSchema}".hallazgos_evaluacion
    (empresa_id, evaluacion_id, respuesta_id, creada_en, id);

CREATE FUNCTION "${flyway:defaultSchema}".inicializar_respuestas_evaluacion()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.estado <> 'BORRADOR' THEN
        RAISE EXCEPTION 'Una evaluación nueva debe iniciar en borrador.' USING ERRCODE = '23514';
    END IF;
    INSERT INTO "${flyway:defaultSchema}".respuestas_evaluacion
        (id, empresa_id, evaluacion_id, version_catalogo_id, elemento_id, tipo, codigo, titulo, grupo)
    SELECT gen_random_uuid(), NEW.empresa_id, NEW.id, NEW.version_catalogo_id,
        e.elemento_id, e.tipo, e.codigo, e.titulo, e.grupo
    FROM comun.contexto_nueva_evaluacion(NEW.empresa_id, NEW.responsable_id,
        NEW.version_catalogo_id, NEW.version_metodologia_id) e;
    RETURN NEW;
END;
$$;
CREATE TRIGGER inicializar_respuestas_evaluacion AFTER INSERT ON "${flyway:defaultSchema}".evaluaciones
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".inicializar_respuestas_evaluacion();

-- A write takes the same evaluation lock used by the application and future lifecycle transitions.
CREATE FUNCTION "${flyway:defaultSchema}".exigir_evaluacion_borrador()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM 1 FROM "${flyway:defaultSchema}".evaluaciones e
    WHERE e.empresa_id = NEW.empresa_id AND e.id = NEW.evaluacion_id AND e.estado = 'BORRADOR'
    FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'La evaluación no es editable.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER exigir_borrador_respuesta BEFORE UPDATE ON "${flyway:defaultSchema}".respuestas_evaluacion
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".exigir_evaluacion_borrador();
CREATE TRIGGER exigir_borrador_hallazgo BEFORE INSERT OR UPDATE ON "${flyway:defaultSchema}".hallazgos_evaluacion
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".exigir_evaluacion_borrador();

REVOKE ALL ON FUNCTION "${flyway:defaultSchema}".inicializar_respuestas_evaluacion(),
    "${flyway:defaultSchema}".exigir_evaluacion_borrador() FROM PUBLIC;
GRANT SELECT ON "${flyway:defaultSchema}".evaluaciones, "${flyway:defaultSchema}".respuestas_evaluacion,
    "${flyway:defaultSchema}".hallazgos_evaluacion TO "${runtime_role}";
GRANT INSERT (id, empresa_id, nombre, fecha_evaluacion, alcance, responsable_id, version_catalogo_id,
    version_metodologia_id, revision, creada_por, creada_en, actualizada_en)
    ON "${flyway:defaultSchema}".evaluaciones TO "${runtime_role}";
GRANT UPDATE (revision, actualizada_en) ON "${flyway:defaultSchema}".evaluaciones TO "${runtime_role}";
GRANT UPDATE (aplicabilidad, justificacion, calificacion, fundamento, actualizada_por, actualizada_en)
    ON "${flyway:defaultSchema}".respuestas_evaluacion TO "${runtime_role}";
GRANT INSERT ON "${flyway:defaultSchema}".hallazgos_evaluacion TO "${runtime_role}";
GRANT UPDATE (descripcion, recomendacion, actualizada_por, actualizada_en)
    ON "${flyway:defaultSchema}".hallazgos_evaluacion TO "${runtime_role}";
