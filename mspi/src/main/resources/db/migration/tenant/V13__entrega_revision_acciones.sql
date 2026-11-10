ALTER TABLE "${flyway:defaultSchema}".evidencias
    DROP CONSTRAINT evidencias_check,
    ADD COLUMN accion_id uuid,
    ADD CONSTRAINT destino_evidencia_exclusivo CHECK (
        (evaluacion_id IS NOT NULL AND respuesta_id IS NOT NULL
            AND implementacion_id IS NULL AND version_implementacion_id IS NULL AND accion_id IS NULL)
        OR (evaluacion_id IS NULL AND respuesta_id IS NULL
            AND implementacion_id IS NOT NULL AND version_implementacion_id IS NOT NULL AND accion_id IS NULL)
        OR (evaluacion_id IS NULL AND respuesta_id IS NULL
            AND implementacion_id IS NULL AND version_implementacion_id IS NULL AND accion_id IS NOT NULL)),
    ADD FOREIGN KEY (empresa_id, accion_id) REFERENCES "${flyway:defaultSchema}".acciones_mejora(empresa_id, id),
    ADD UNIQUE (empresa_id, accion_id, id);

ALTER TABLE "${flyway:defaultSchema}".eventos_accion
    DROP CONSTRAINT eventos_accion_tipo_evento_check,
    ADD COLUMN entrega_id uuid,
    ADD COLUMN evidencia_id uuid,
    ADD UNIQUE (empresa_id, accion_id, id),
    ADD FOREIGN KEY (empresa_id, accion_id, evidencia_id)
        REFERENCES "${flyway:defaultSchema}".evidencias(empresa_id, accion_id, id),
    ADD CONSTRAINT tipo_evento_accion CHECK (tipo_evento IN
        ('CREADA','ACTUALIZADA','CANCELADA','INICIADA','AVANCE','SOLICITUD_REVISION','APROBADA','DEVUELTA')),
    ADD CHECK ((tipo_evento IN ('APROBADA','DEVUELTA')) = (entrega_id IS NOT NULL)),
    ADD CHECK (tipo_evento NOT IN ('AVANCE','SOLICITUD_REVISION','APROBADA','DEVUELTA')
        OR nullif(btrim(comentario), '') IS NOT NULL),
    ADD CHECK (evidencia_id IS NULL OR tipo_evento = 'SOLICITUD_REVISION');
ALTER TABLE "${flyway:defaultSchema}".eventos_accion
    ADD FOREIGN KEY (empresa_id, accion_id, entrega_id)
        REFERENCES "${flyway:defaultSchema}".eventos_accion(empresa_id, accion_id, id);
CREATE UNIQUE INDEX decision_unica_accion ON "${flyway:defaultSchema}".eventos_accion(entrega_id)
    WHERE entrega_id IS NOT NULL;

ALTER TABLE "${flyway:defaultSchema}".acciones_mejora
    ADD COLUMN entrega_vigente_id uuid,
    ADD COLUMN finalizada_en timestamptz,
    ADD FOREIGN KEY (empresa_id, id, entrega_vigente_id)
        REFERENCES "${flyway:defaultSchema}".eventos_accion(empresa_id, accion_id, id),
    ADD CHECK ((estado = 'COMPLETADA') = (finalizada_en IS NOT NULL)),
    ADD CHECK (estado NOT IN ('EN_REVISION','COMPLETADA') OR entrega_vigente_id IS NOT NULL);

CREATE OR REPLACE FUNCTION "${flyway:defaultSchema}".proteger_transicion_accion()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.estado IN ('COMPLETADA','CANCELADA') OR NEW.revision <> OLD.revision + 1 THEN
        RAISE EXCEPTION 'Estado terminal o revisión incompatible.' USING ERRCODE = '23514';
    END IF;
    IF NOT (NEW.estado = 'CANCELADA'
        OR (OLD.estado = NEW.estado AND OLD.estado IN ('PENDIENTE','EN_EJECUCION'))
        OR (OLD.estado = 'PENDIENTE' AND NEW.estado = 'EN_EJECUCION')
        OR (OLD.estado = 'EN_EJECUCION' AND NEW.estado = 'EN_REVISION')
        OR (OLD.estado = 'EN_REVISION' AND NEW.estado IN ('EN_EJECUCION','COMPLETADA'))) THEN
        RAISE EXCEPTION 'Transición de acción incompatible.' USING ERRCODE = '23514';
    END IF;
    IF NEW.estado = 'CANCELADA' AND nullif(btrim(NEW.motivo_cancelacion), '') IS NULL THEN
        RAISE EXCEPTION 'La cancelación requiere motivo.' USING ERRCODE = '23514';
    END IF;
    IF OLD.estado = 'EN_REVISION' AND
       ROW(OLD.riesgo_id, OLD.hallazgo_id, OLD.descripcion, OLD.resultado_esperado, OLD.responsable_membresia_id,
           OLD.fecha_limite, OLD.evidencia_requerida, OLD.descripcion_evidencia, OLD.justificacion_sin_evidencia,
           OLD.resumen_contexto_ra) IS DISTINCT FROM
       ROW(NEW.riesgo_id, NEW.hallazgo_id, NEW.descripcion, NEW.resultado_esperado, NEW.responsable_membresia_id,
           NEW.fecha_limite, NEW.evidencia_requerida, NEW.descripcion_evidencia, NEW.justificacion_sin_evidencia,
           NEW.resumen_contexto_ra) THEN
        RAISE EXCEPTION 'El alcance de la entrega está congelado.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION "${flyway:defaultSchema}".exigir_accion_ejecutable_para_evidencia()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM 1 FROM "${flyway:defaultSchema}".acciones_mejora a
     WHERE a.empresa_id = NEW.empresa_id AND a.id = NEW.accion_id
       AND a.estado IN ('PENDIENTE','EN_EJECUCION') FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'La acción no admite nuevos archivos.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER exigir_accion_ejecutable_para_evidencia
    BEFORE INSERT OR UPDATE OF estado_carga ON "${flyway:defaultSchema}".evidencias
    FOR EACH ROW WHEN (NEW.accion_id IS NOT NULL)
    EXECUTE FUNCTION "${flyway:defaultSchema}".exigir_accion_ejecutable_para_evidencia();
REVOKE ALL ON FUNCTION "${flyway:defaultSchema}".exigir_accion_ejecutable_para_evidencia() FROM PUBLIC;

GRANT INSERT (accion_id) ON "${flyway:defaultSchema}".evidencias TO "${runtime_role}";
GRANT UPDATE (entrega_vigente_id, finalizada_en) ON "${flyway:defaultSchema}".acciones_mejora TO "${runtime_role}";
