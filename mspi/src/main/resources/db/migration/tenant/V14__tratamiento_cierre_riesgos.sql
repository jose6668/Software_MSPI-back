ALTER TABLE "${flyway:defaultSchema}".riesgos
    DROP CONSTRAINT riesgos_estado_check,
    DROP CONSTRAINT riesgos_check,
    ADD CONSTRAINT estado_riesgo CHECK (estado IN ('IDENTIFICADO','EN_ANALISIS','EN_TRATAMIENTO','EN_SEGUIMIENTO','CERRADO')),
    ADD CHECK (inherente_vigente_id IS NULL OR estado <> 'IDENTIFICADO'),
    ADD COLUMN riesgo_relacionado_id uuid,
    ADD COLUMN justificacion_cierre text,
    ADD COLUMN cerrado_por uuid,
    ADD COLUMN cerrado_en timestamptz,
    ADD COLUMN aceptacion_cierre_id uuid,
    ADD COLUMN cancelaciones_cierre jsonb,
    ADD FOREIGN KEY (empresa_id, riesgo_relacionado_id) REFERENCES "${flyway:defaultSchema}".riesgos(empresa_id,id),
    ADD CHECK (riesgo_relacionado_id IS DISTINCT FROM id),
    ADD CHECK (CASE WHEN estado = 'CERRADO' THEN
        cerrado_en IS NOT NULL AND cerrado_por IS NOT NULL AND nullif(btrim(justificacion_cierre),'') IS NOT NULL
        AND cancelaciones_cierre IS NOT NULL AND jsonb_typeof(cancelaciones_cierre) = 'object'
        AND inherente_vigente_id IS NOT NULL AND residual_vigente_id IS NOT NULL
        AND tratamiento_propuesto IS NOT NULL
        AND (tratamiento_propuesto = 'ACEPTAR') = (aceptacion_cierre_id IS NOT NULL)
        ELSE cerrado_en IS NULL AND cerrado_por IS NULL AND justificacion_cierre IS NULL
        AND cancelaciones_cierre IS NULL AND aceptacion_cierre_id IS NULL END);

CREATE TABLE "${flyway:defaultSchema}".aceptaciones_riesgo (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL,
    riesgo_id uuid NOT NULL,
    inherente_id uuid NOT NULL,
    residual_id uuid NOT NULL,
    justificacion text NOT NULL CHECK (btrim(justificacion) <> ''),
    fecha_proxima_revision date NOT NULL,
    aprobada_por uuid NOT NULL,
    aprobada_en timestamptz NOT NULL,
    revision_riesgo bigint NOT NULL CHECK (revision_riesgo > 0),
    UNIQUE (empresa_id, riesgo_id, id),
    UNIQUE (empresa_id, riesgo_id, revision_riesgo),
    FOREIGN KEY (empresa_id, riesgo_id, inherente_id) REFERENCES "${flyway:defaultSchema}".valoraciones_inherentes(empresa_id,riesgo_id,id),
    FOREIGN KEY (empresa_id, riesgo_id, residual_id) REFERENCES "${flyway:defaultSchema}".valoraciones_residuales(empresa_id,riesgo_id,id)
);
ALTER TABLE "${flyway:defaultSchema}".riesgos ADD FOREIGN KEY (empresa_id,id,aceptacion_cierre_id)
    REFERENCES "${flyway:defaultSchema}".aceptaciones_riesgo(empresa_id,riesgo_id,id);

CREATE FUNCTION "${flyway:defaultSchema}".proteger_estado_riesgo() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.estado = 'CERRADO' OR (OLD.estado <> NEW.estado AND NOT (
        (OLD.estado = 'IDENTIFICADO' AND NEW.estado = 'EN_ANALISIS')
        OR (OLD.estado = 'EN_ANALISIS' AND NEW.estado IN ('EN_TRATAMIENTO','EN_SEGUIMIENTO'))
        OR (OLD.estado = 'EN_TRATAMIENTO' AND NEW.estado = 'EN_SEGUIMIENTO')
        OR (OLD.estado = 'EN_SEGUIMIENTO' AND NEW.estado IN ('EN_ANALISIS','EN_TRATAMIENTO','CERRADO')))) THEN
        RAISE EXCEPTION 'Transición de riesgo incompatible.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER proteger_estado_riesgo BEFORE UPDATE ON "${flyway:defaultSchema}".riesgos
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".proteger_estado_riesgo();
REVOKE ALL ON FUNCTION "${flyway:defaultSchema}".proteger_estado_riesgo() FROM PUBLIC;

CREATE FUNCTION "${flyway:defaultSchema}".proteger_accion_de_riesgo_cerrado() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE relacionado record;
DECLARE previo uuid;
BEGIN
    IF TG_OP = 'UPDATE' THEN previo := OLD.riesgo_id; END IF;
    FOR relacionado IN SELECT estado FROM "${flyway:defaultSchema}".riesgos
        WHERE empresa_id = NEW.empresa_id AND (id = NEW.riesgo_id OR id = previo) ORDER BY id FOR SHARE
    LOOP
        IF relacionado.estado = 'CERRADO' THEN
            RAISE EXCEPTION 'El riesgo cerrado conserva sus acciones.' USING ERRCODE = '23514', CONSTRAINT = 'riesgo_cerrado_accion';
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;
CREATE TRIGGER proteger_accion_de_riesgo_cerrado BEFORE INSERT OR UPDATE ON "${flyway:defaultSchema}".acciones_mejora
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".proteger_accion_de_riesgo_cerrado();
REVOKE ALL ON FUNCTION "${flyway:defaultSchema}".proteger_accion_de_riesgo_cerrado() FROM PUBLIC;

GRANT SELECT, INSERT ON "${flyway:defaultSchema}".aceptaciones_riesgo TO "${runtime_role}";
GRANT UPDATE (justificacion_cierre,cerrado_por,cerrado_en,aceptacion_cierre_id,cancelaciones_cierre)
    ON "${flyway:defaultSchema}".riesgos TO "${runtime_role}";
