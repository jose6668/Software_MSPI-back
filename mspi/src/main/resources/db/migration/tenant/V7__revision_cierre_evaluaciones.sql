ALTER TABLE "${flyway:defaultSchema}".evaluaciones
    ADD COLUMN evaluacion_anterior uuid,
    ADD COLUMN motivo_revision varchar(2000),
    ADD COLUMN cerrada_por uuid,
    ADD COLUMN cerrada_en timestamptz,
    ADD COLUMN instantanea_cierre jsonb,
    ADD CONSTRAINT correccion_original_local FOREIGN KEY (empresa_id, evaluacion_anterior)
        REFERENCES "${flyway:defaultSchema}".evaluaciones(empresa_id, id),
    ADD CONSTRAINT correccion_sucesora_unica UNIQUE (empresa_id, evaluacion_anterior),
    ADD CHECK (evaluacion_anterior IS NULL OR evaluacion_anterior <> id),
    ADD CHECK ((evaluacion_anterior IS NULL AND motivo_revision IS NULL) OR
        (evaluacion_anterior IS NOT NULL AND motivo_revision IS NOT NULL AND btrim(motivo_revision) <> '')),
    ADD CHECK ((estado = 'CERRADA' AND cerrada_por IS NOT NULL AND cerrada_en IS NOT NULL
        AND instantanea_cierre IS NOT NULL AND jsonb_typeof(instantanea_cierre) = 'object') OR
        (estado <> 'CERRADA' AND cerrada_por IS NULL AND cerrada_en IS NULL AND instantanea_cierre IS NULL));

ALTER TABLE "${flyway:defaultSchema}".respuestas_evaluacion
    ADD COLUMN respuesta_anterior uuid REFERENCES "${flyway:defaultSchema}".respuestas_evaluacion(id),
    ADD COLUMN heredada_confirmada boolean NOT NULL DEFAULT true,
    ADD CHECK (respuesta_anterior IS NOT NULL OR heredada_confirmada);

CREATE OR REPLACE FUNCTION "${flyway:defaultSchema}".inicializar_respuestas_evaluacion()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.estado <> 'BORRADOR' THEN
        RAISE EXCEPTION 'Una evaluación nueva debe iniciar en borrador.' USING ERRCODE = '23514';
    END IF;

    IF NEW.evaluacion_anterior IS NULL THEN
        INSERT INTO "${flyway:defaultSchema}".respuestas_evaluacion
            (id, empresa_id, evaluacion_id, version_catalogo_id, elemento_id, tipo, codigo, titulo, grupo)
        SELECT gen_random_uuid(), NEW.empresa_id, NEW.id, NEW.version_catalogo_id,
            e.elemento_id, e.tipo, e.codigo, e.titulo, e.grupo
        FROM comun.contexto_nueva_evaluacion(NEW.empresa_id, NEW.responsable_id,
            NEW.version_catalogo_id, NEW.version_metodologia_id) e;
    ELSE
        PERFORM comun.validar_responsable_correccion(NEW.empresa_id, NEW.responsable_id);
        INSERT INTO "${flyway:defaultSchema}".respuestas_evaluacion
            (id, empresa_id, evaluacion_id, version_catalogo_id, elemento_id, tipo, codigo, titulo, grupo,
             aplicabilidad, justificacion, calificacion, fundamento, actualizada_por, actualizada_en,
             respuesta_anterior, heredada_confirmada)
        SELECT gen_random_uuid(), NEW.empresa_id, NEW.id, r.version_catalogo_id, r.elemento_id, r.tipo, r.codigo,
            r.titulo, r.grupo, r.aplicabilidad, r.justificacion, r.calificacion, r.fundamento,
            r.actualizada_por, r.actualizada_en, r.id, false
        FROM "${flyway:defaultSchema}".respuestas_evaluacion r
        JOIN "${flyway:defaultSchema}".evaluaciones anterior ON anterior.id = r.evaluacion_id
        WHERE r.empresa_id = NEW.empresa_id AND anterior.id = NEW.evaluacion_anterior
          AND anterior.estado = 'CERRADA' AND anterior.version_catalogo_id = NEW.version_catalogo_id
          AND anterior.version_metodologia_id = NEW.version_metodologia_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'La evaluación de origen no se puede corregir.' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION "${flyway:defaultSchema}".proteger_evaluacion_cerrada()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.estado = 'CERRADA' THEN
        RAISE EXCEPTION 'La evaluación cerrada es inmutable.' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER proteger_evaluacion_cerrada BEFORE UPDATE OR DELETE ON "${flyway:defaultSchema}".evaluaciones
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".proteger_evaluacion_cerrada();

CREATE FUNCTION "${flyway:defaultSchema}".transicionar_evaluacion(p_empresa uuid, p_evaluacion uuid,
    p_revision bigint, p_destino text, p_actor uuid, p_cierre jsonb)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    afectadas integer;
BEGIN
    UPDATE "${flyway:defaultSchema}".evaluaciones e
    SET estado = p_destino, revision = revision + 1,
        actualizada_en = greatest(clock_timestamp(), actualizada_en + interval '1 microsecond'),
        cerrada_en = CASE WHEN p_destino = 'CERRADA' THEN clock_timestamp() END,
        cerrada_por = CASE WHEN p_destino = 'CERRADA' THEN p_actor END,
        instantanea_cierre = CASE WHEN p_destino = 'CERRADA' THEN p_cierre END
    WHERE e.empresa_id = p_empresa AND e.id = p_evaluacion AND e.revision = p_revision
      AND ((e.estado = 'BORRADOR' AND p_destino = 'EN_REVISION') OR
           (e.estado = 'EN_REVISION' AND p_destino IN ('BORRADOR', 'CERRADA')))
      AND comun.puede_revisar_evaluacion(p_empresa, p_actor, e.version_catalogo_id,
          e.version_metodologia_id, p_destino <> 'BORRADOR')
      AND (p_destino = 'BORRADOR' OR (
        p_cierre->>'politica' = 'F1-1_EVIDENCIA_V1'
        AND (p_destino <> 'CERRADA' OR (
            p_cierre->'resultados'->>'evaluacionId' = e.id::text
            AND p_cierre->'resultados'->>'revision' = (e.revision + 1)::text
        ))
        AND EXISTS (SELECT 1 FROM "${flyway:defaultSchema}".respuestas_evaluacion r WHERE r.evaluacion_id = e.id)
        AND NOT EXISTS (
            SELECT 1 FROM "${flyway:defaultSchema}".respuestas_evaluacion r WHERE r.evaluacion_id = e.id
              AND (NOT r.heredada_confirmada OR r.aplicabilidad = 'SIN_DECIDIR' OR
                (r.aplicabilidad = 'APLICA' AND (r.calificacion IS NULL OR r.fundamento IS NULL)))
        )
        AND NOT EXISTS (
            SELECT 1 FROM "${flyway:defaultSchema}".respuestas_evaluacion r
            WHERE r.evaluacion_id = e.id AND r.calificacion > 0
              AND NOT EXISTS (
                SELECT 1 FROM "${flyway:defaultSchema}".evidencias a
                WHERE a.empresa_id = e.empresa_id AND a.estado_carga = 'DISPONIBLE'
                  AND a.id = (p_cierre->'evidencias'->(r.id::text)->>'evidenciaId')::uuid
                  AND a.huella_contenido = p_cierre->'evidencias'->(r.id::text)->>'sha256'
                  AND (a.respuesta_id = r.id OR a.id = (
                    SELECT (o.instantanea_cierre->'evidencias'->(r.respuesta_anterior::text)->>'evidenciaId')::uuid
                    FROM "${flyway:defaultSchema}".evaluaciones o WHERE o.id = e.evaluacion_anterior
                  ))
              )
        )));
    GET DIAGNOSTICS afectadas = ROW_COUNT;
    RETURN afectadas;
END;
$$;

CREATE FUNCTION "${flyway:defaultSchema}".corregir_evaluacion(p_empresa uuid, p_original uuid, p_revision bigint,
    p_id uuid, p_responsable uuid, p_motivo text, p_actor uuid)
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    afectadas integer;
BEGIN
    INSERT INTO "${flyway:defaultSchema}".evaluaciones
        (id, empresa_id, nombre, fecha_evaluacion, alcance, responsable_id, version_catalogo_id, version_metodologia_id,
         revision, creada_por, creada_en, actualizada_en, evaluacion_anterior, motivo_revision)
    SELECT p_id, e.empresa_id, e.nombre, e.fecha_evaluacion, e.alcance, p_responsable, e.version_catalogo_id,
        e.version_metodologia_id, 0, p_actor, statement_timestamp(), statement_timestamp(), e.id, p_motivo
    FROM "${flyway:defaultSchema}".evaluaciones e
    WHERE e.empresa_id = p_empresa AND e.id = p_original AND e.revision = p_revision AND e.estado = 'CERRADA'
      AND comun.puede_revisar_evaluacion(p_empresa, p_actor, e.version_catalogo_id, e.version_metodologia_id, false);
    GET DIAGNOSTICS afectadas = ROW_COUNT;
    RETURN afectadas;
END;
$$;

REVOKE ALL ON FUNCTION "${flyway:defaultSchema}".proteger_evaluacion_cerrada(),
    "${flyway:defaultSchema}".transicionar_evaluacion(uuid, uuid, bigint, text, uuid, jsonb),
    "${flyway:defaultSchema}".corregir_evaluacion(uuid, uuid, bigint, uuid, uuid, text, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION "${flyway:defaultSchema}".transicionar_evaluacion(uuid, uuid, bigint, text, uuid, jsonb),
    "${flyway:defaultSchema}".corregir_evaluacion(uuid, uuid, bigint, uuid, uuid, text, uuid) TO "${runtime_role}";
GRANT UPDATE (heredada_confirmada) ON "${flyway:defaultSchema}".respuestas_evaluacion TO "${runtime_role}";
