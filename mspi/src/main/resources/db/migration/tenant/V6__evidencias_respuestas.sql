CREATE TABLE "${flyway:defaultSchema}".uso_evidencias (
    empresa_id uuid PRIMARY KEY REFERENCES "${flyway:defaultSchema}".contexto_empresa(empresa_id),
    bytes_ocupados bigint NOT NULL DEFAULT 0 CHECK (bytes_ocupados >= 0)
);
INSERT INTO "${flyway:defaultSchema}".uso_evidencias (empresa_id) VALUES ('${empresa_id}'::uuid);

CREATE TABLE "${flyway:defaultSchema}".evidencias (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL,
    evaluacion_id uuid NOT NULL,
    respuesta_id uuid NOT NULL,
    modalidad text NOT NULL DEFAULT 'ARCHIVO' CHECK (modalidad = 'ARCHIVO'),
    nombre_original varchar(255) NOT NULL CHECK (btrim(nombre_original) <> ''),
    tipo_detectado text NOT NULL CHECK (tipo_detectado IN ('application/pdf', 'image/png', 'image/jpeg')),
    tamano_bytes integer NOT NULL CHECK (tamano_bytes BETWEEN 1 AND 10000000),
    huella_contenido char(64) NOT NULL CHECK (huella_contenido ~ '^[0-9a-f]{64}$'),
    descripcion varchar(1000),
    estado_carga text NOT NULL DEFAULT 'PENDIENTE' CHECK (estado_carga IN ('PENDIENTE', 'DISPONIBLE', 'FALLIDA')),
    creada_por uuid NOT NULL,
    creada_en timestamptz NOT NULL,
    FOREIGN KEY (empresa_id, evaluacion_id, respuesta_id)
        REFERENCES "${flyway:defaultSchema}".respuestas_evaluacion(empresa_id, evaluacion_id, id)
);
CREATE INDEX evidencias_disponibles_por_respuesta ON "${flyway:defaultSchema}".evidencias
    (empresa_id, evaluacion_id, respuesta_id, creada_en, id) WHERE estado_carga = 'DISPONIBLE';

CREATE FUNCTION "${flyway:defaultSchema}".validar_publicacion_evidencia()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.estado_carga <> 'PENDIENTE' OR NEW.estado_carga NOT IN ('DISPONIBLE', 'FALLIDA') THEN
        RAISE EXCEPTION 'La carga ya es terminal o la transición no es válida.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER validar_publicacion_evidencia BEFORE UPDATE ON "${flyway:defaultSchema}".evidencias
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".validar_publicacion_evidencia();
CREATE TRIGGER exigir_borrador_evidencia BEFORE INSERT OR UPDATE ON "${flyway:defaultSchema}".evidencias
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".exigir_evaluacion_borrador();
REVOKE ALL ON FUNCTION "${flyway:defaultSchema}".validar_publicacion_evidencia() FROM PUBLIC;

GRANT SELECT ON "${flyway:defaultSchema}".uso_evidencias, "${flyway:defaultSchema}".evidencias TO "${runtime_role}";
GRANT UPDATE (bytes_ocupados) ON "${flyway:defaultSchema}".uso_evidencias TO "${runtime_role}";
GRANT INSERT (id, empresa_id, evaluacion_id, respuesta_id, nombre_original, tipo_detectado, tamano_bytes,
    huella_contenido, descripcion, creada_por, creada_en) ON "${flyway:defaultSchema}".evidencias TO "${runtime_role}";
GRANT UPDATE (estado_carga) ON "${flyway:defaultSchema}".evidencias TO "${runtime_role}";
