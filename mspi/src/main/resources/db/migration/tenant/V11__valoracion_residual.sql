CREATE TABLE "${flyway:defaultSchema}".valoraciones_residuales (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL,
    riesgo_id uuid NOT NULL,
    inherente_id uuid NOT NULL,
    version_metodologia_id uuid NOT NULL REFERENCES comun.versiones_metodologia(id),
    anterior_id uuid,
    motivo_reevaluacion varchar(2000),
    probabilidad text CHECK (probabilidad IN ('MUY_BAJA','BAJA','MEDIA','ALTA','MUY_ALTA')),
    impacto text CHECK (impacto IN ('LEVE','MENOR','MODERADO','MAYOR','CATASTROFICO')),
    periodo varchar(150),
    fundamento text,
    supuestos text,
    fuente text,
    sin_mitigacion boolean,
    justificacion_sin_mitigacion text,
    seleccion uuid[] NOT NULL CHECK (cardinality(seleccion) <= 100 AND array_position(seleccion, NULL) IS NULL),
    probabilidad_indice numeric(3,2),
    impacto_indice numeric(3,2),
    nivel text CHECK (nivel IN ('BAJO','MODERADO','ALTO','EXTREMO')),
    creada_por uuid NOT NULL,
    creada_en timestamptz NOT NULL,
    confirmada_por uuid,
    confirmada_en timestamptz,
    UNIQUE (empresa_id, riesgo_id, id),
    FOREIGN KEY (empresa_id, riesgo_id) REFERENCES "${flyway:defaultSchema}".riesgos(empresa_id,id),
    FOREIGN KEY (empresa_id, riesgo_id, inherente_id)
        REFERENCES "${flyway:defaultSchema}".valoraciones_inherentes(empresa_id,riesgo_id,id),
    FOREIGN KEY (empresa_id, riesgo_id, anterior_id)
        REFERENCES "${flyway:defaultSchema}".valoraciones_residuales(empresa_id,riesgo_id,id),
    CHECK ((anterior_id IS NULL AND motivo_reevaluacion IS NULL)
        OR (anterior_id IS NOT NULL AND anterior_id <> id AND motivo_reevaluacion IS NOT NULL
            AND btrim(motivo_reevaluacion) <> '')),
    CHECK ((confirmada_en IS NULL AND confirmada_por IS NULL AND nivel IS NULL
            AND probabilidad_indice IS NULL AND impacto_indice IS NULL)
        OR (confirmada_en IS NOT NULL AND confirmada_por IS NOT NULL AND nivel IS NOT NULL
            AND probabilidad_indice IS NOT NULL AND impacto_indice IS NOT NULL
            AND probabilidad IS NOT NULL AND impacto IS NOT NULL AND nullif(btrim(periodo),'') IS NOT NULL
            AND nullif(btrim(fundamento),'') IS NOT NULL AND nullif(btrim(supuestos),'') IS NOT NULL
            AND nullif(btrim(fuente),'') IS NOT NULL AND sin_mitigacion IS NOT NULL
            AND ((sin_mitigacion AND cardinality(seleccion) = 0 AND nullif(btrim(justificacion_sin_mitigacion),'') IS NOT NULL)
                OR (NOT sin_mitigacion AND cardinality(seleccion) > 0 AND justificacion_sin_mitigacion IS NULL))))
);
CREATE UNIQUE INDEX riesgo_un_borrador_residual ON "${flyway:defaultSchema}".valoraciones_residuales (empresa_id,riesgo_id)
    WHERE confirmada_en IS NULL;
CREATE INDEX residuales_por_riesgo ON "${flyway:defaultSchema}".valoraciones_residuales (empresa_id,riesgo_id,creada_en DESC,id);
CREATE UNIQUE INDEX residual_sucesor_confirmado ON "${flyway:defaultSchema}".valoraciones_residuales (anterior_id)
    WHERE confirmada_en IS NOT NULL AND anterior_id IS NOT NULL;
ALTER TABLE "${flyway:defaultSchema}".riesgos ADD COLUMN residual_vigente_id uuid,
    ADD FOREIGN KEY (empresa_id,id,residual_vigente_id)
        REFERENCES "${flyway:defaultSchema}".valoraciones_residuales(empresa_id,riesgo_id,id);

ALTER TABLE "${flyway:defaultSchema}".verificaciones_control
    ADD UNIQUE (empresa_id,implementacion_id,version_id,id);

CREATE TABLE "${flyway:defaultSchema}".residual_verificaciones (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL,
    riesgo_id uuid NOT NULL,
    residual_id uuid NOT NULL,
    implementacion_id uuid NOT NULL,
    version_id uuid NOT NULL,
    verificacion_id uuid NOT NULL,
    evidencia_aplicacion_id uuid NOT NULL REFERENCES "${flyway:defaultSchema}".evidencias(id),
    evidencia_verificacion_id uuid NOT NULL REFERENCES "${flyway:defaultSchema}".evidencias(id),
    vence_en timestamptz NOT NULL,
    UNIQUE (residual_id,implementacion_id),
    FOREIGN KEY (empresa_id,riesgo_id,residual_id)
        REFERENCES "${flyway:defaultSchema}".valoraciones_residuales(empresa_id,riesgo_id,id),
    FOREIGN KEY (empresa_id,riesgo_id,implementacion_id)
        REFERENCES "${flyway:defaultSchema}".riesgos_implementaciones(empresa_id,riesgo_id,implementacion_id),
    FOREIGN KEY (empresa_id,implementacion_id,version_id,verificacion_id)
        REFERENCES "${flyway:defaultSchema}".verificaciones_control(empresa_id,implementacion_id,version_id,id)
);
CREATE INDEX residuales_por_control ON "${flyway:defaultSchema}".residual_verificaciones (empresa_id,implementacion_id);
CREATE INDEX residuales_por_evidencia_aplicacion ON "${flyway:defaultSchema}".residual_verificaciones (empresa_id,evidencia_aplicacion_id);
CREATE INDEX residuales_por_evidencia_verificacion ON "${flyway:defaultSchema}".residual_verificaciones (empresa_id,evidencia_verificacion_id);

CREATE TABLE "${flyway:defaultSchema}".avisos_revision_residual (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    empresa_id uuid NOT NULL,
    riesgo_id uuid NOT NULL,
    residual_id uuid NOT NULL,
    causa text NOT NULL CHECK (causa IN ('CONTEXTO_CAMBIADO','INHERENTE_SUSTITUIDA',
        'VERSION_CONTROL_CAMBIADA','VERIFICACION_SUSTITUIDA','EVIDENCIA_BLOQUEADA','CONTROL_VINCULADO')),
    referencia_id uuid NOT NULL,
    detectado_en timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (empresa_id,riesgo_id,residual_id)
        REFERENCES "${flyway:defaultSchema}".valoraciones_residuales(empresa_id,riesgo_id,id),
    UNIQUE (residual_id,causa,referencia_id)
);

CREATE FUNCTION "${flyway:defaultSchema}".proteger_residual_confirmado()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.confirmada_en IS NOT NULL THEN
            RAISE EXCEPTION 'El residual debe iniciar en borrador.' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.confirmada_en IS NOT NULL THEN
        RAISE EXCEPTION 'La valoración residual confirmada es inmutable.' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    IF NEW.confirmada_en IS NOT NULL THEN
        PERFORM 1 FROM "${flyway:defaultSchema}".riesgos r
        JOIN "${flyway:defaultSchema}".valoraciones_inherentes i
          ON i.empresa_id = r.empresa_id AND i.riesgo_id = r.id AND i.id = r.inherente_vigente_id
        WHERE r.empresa_id = NEW.empresa_id AND r.id = NEW.riesgo_id AND i.id = NEW.inherente_id
          AND r.motivo_revision IS NULL AND i.confirmada_en IS NOT NULL AND i.contexto = r.contexto
          AND i.version_metodologia_id = NEW.version_metodologia_id
          AND (NOT NEW.sin_mitigacion OR (i.probabilidad = NEW.probabilidad AND i.impacto = NEW.impacto));
        IF NOT FOUND OR cardinality(NEW.seleccion) <> (
            SELECT count(*) FROM "${flyway:defaultSchema}".residual_verificaciones d
            WHERE d.residual_id = NEW.id AND d.verificacion_id = ANY(NEW.seleccion)
        ) OR EXISTS (
            SELECT 1 FROM "${flyway:defaultSchema}".residual_verificaciones d
            WHERE d.residual_id = NEW.id AND (d.vence_en <= NEW.confirmada_en OR NOT d.verificacion_id = ANY(NEW.seleccion))
        ) THEN
            RAISE EXCEPTION 'El residual no tiene una base o selección válida.' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER proteger_residual_confirmado BEFORE INSERT OR UPDATE OR DELETE ON "${flyway:defaultSchema}".valoraciones_residuales
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".proteger_residual_confirmado();

-- Association rows must be fixed before confirmation. The runtime cannot change them afterward.
CREATE FUNCTION "${flyway:defaultSchema}".exigir_residual_borrador()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM 1 FROM "${flyway:defaultSchema}".valoraciones_residuales r
    WHERE r.empresa_id = NEW.empresa_id AND r.riesgo_id = NEW.riesgo_id AND r.id = NEW.residual_id
      AND r.confirmada_en IS NULL
    FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'El residual no admite vínculos nuevos.' USING ERRCODE = '23514';
    END IF;
    PERFORM 1 FROM "${flyway:defaultSchema}".verificaciones_control v
    JOIN "${flyway:defaultSchema}".aplicaciones_control a ON a.version_id = v.version_id
    WHERE v.empresa_id = NEW.empresa_id AND v.id = NEW.verificacion_id
      AND v.evidencia_id = NEW.evidencia_verificacion_id AND a.evidencia_id = NEW.evidencia_aplicacion_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'La evidencia no corresponde a la verificación.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER exigir_residual_borrador BEFORE INSERT ON "${flyway:defaultSchema}".residual_verificaciones
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".exigir_residual_borrador();

CREATE FUNCTION "${flyway:defaultSchema}".avisar_cambio_residual()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF TG_TABLE_NAME = 'riesgos' THEN
        IF NEW.residual_vigente_id IS NULL THEN RETURN NEW; END IF;
        IF NEW.contexto IS DISTINCT FROM OLD.contexto THEN
            INSERT INTO "${flyway:defaultSchema}".avisos_revision_residual
                (empresa_id,riesgo_id,residual_id,causa,referencia_id)
            VALUES (NEW.empresa_id,NEW.id,NEW.residual_vigente_id,'CONTEXTO_CAMBIADO',NEW.id)
            ON CONFLICT DO NOTHING;
        END IF;
        IF NEW.inherente_vigente_id IS DISTINCT FROM OLD.inherente_vigente_id AND NEW.inherente_vigente_id IS NOT NULL THEN
            INSERT INTO "${flyway:defaultSchema}".avisos_revision_residual
                (empresa_id,riesgo_id,residual_id,causa,referencia_id)
            VALUES (NEW.empresa_id,NEW.id,NEW.residual_vigente_id,'INHERENTE_SUSTITUIDA',NEW.inherente_vigente_id)
            ON CONFLICT DO NOTHING;
        END IF;
    ELSIF TG_TABLE_NAME = 'riesgos_implementaciones' THEN
        INSERT INTO "${flyway:defaultSchema}".avisos_revision_residual
            (empresa_id,riesgo_id,residual_id,causa,referencia_id)
        SELECT r.empresa_id,r.id,r.residual_vigente_id,'CONTROL_VINCULADO',NEW.implementacion_id
        FROM "${flyway:defaultSchema}".riesgos r
        WHERE r.empresa_id = NEW.empresa_id AND r.id = NEW.riesgo_id AND r.residual_vigente_id IS NOT NULL
        ON CONFLICT DO NOTHING;
    ELSIF TG_TABLE_NAME = 'evidencias' THEN
        INSERT INTO "${flyway:defaultSchema}".avisos_revision_residual
            (empresa_id,riesgo_id,residual_id,causa,referencia_id)
        SELECT d.empresa_id,d.riesgo_id,d.residual_id,'EVIDENCIA_BLOQUEADA',NEW.id
        FROM "${flyway:defaultSchema}".residual_verificaciones d
        JOIN "${flyway:defaultSchema}".riesgos r ON r.id = d.riesgo_id AND r.residual_vigente_id = d.residual_id
        WHERE d.empresa_id = NEW.empresa_id AND NEW.id IN (d.evidencia_aplicacion_id,d.evidencia_verificacion_id)
        ON CONFLICT DO NOTHING;
    ELSIF TG_TABLE_NAME = 'implementaciones_control' THEN
        INSERT INTO "${flyway:defaultSchema}".avisos_revision_residual
            (empresa_id,riesgo_id,residual_id,causa,referencia_id)
        SELECT l.empresa_id,l.riesgo_id,r.residual_vigente_id,'VERSION_CONTROL_CAMBIADA',NEW.id
        FROM "${flyway:defaultSchema}".riesgos_implementaciones l
        JOIN "${flyway:defaultSchema}".riesgos r ON r.id = l.riesgo_id AND r.empresa_id = l.empresa_id
        WHERE l.empresa_id = NEW.empresa_id AND l.implementacion_id = NEW.id AND r.residual_vigente_id IS NOT NULL
        ON CONFLICT DO NOTHING;
    ELSE
        INSERT INTO "${flyway:defaultSchema}".avisos_revision_residual
            (empresa_id,riesgo_id,residual_id,causa,referencia_id)
        SELECT d.empresa_id,d.riesgo_id,d.residual_id,'VERIFICACION_SUSTITUIDA',NEW.id
        FROM "${flyway:defaultSchema}".residual_verificaciones d
        JOIN "${flyway:defaultSchema}".riesgos r ON r.id = d.riesgo_id AND r.residual_vigente_id = d.residual_id
        WHERE d.empresa_id = NEW.empresa_id AND d.implementacion_id = NEW.implementacion_id
        ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER avisar_cambio_base AFTER UPDATE OF contexto,inherente_vigente_id ON "${flyway:defaultSchema}".riesgos
    FOR EACH ROW WHEN (NEW.contexto IS DISTINCT FROM OLD.contexto OR NEW.inherente_vigente_id IS DISTINCT FROM OLD.inherente_vigente_id)
    EXECUTE FUNCTION "${flyway:defaultSchema}".avisar_cambio_residual();
CREATE TRIGGER avisar_vinculo AFTER INSERT ON "${flyway:defaultSchema}".riesgos_implementaciones
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".avisar_cambio_residual();
CREATE TRIGGER avisar_cuarentena AFTER UPDATE OF estado_inspeccion ON "${flyway:defaultSchema}".evidencias
    FOR EACH ROW WHEN (NEW.estado_inspeccion = 'BLOQUEADA' AND OLD.estado_inspeccion <> 'BLOQUEADA')
    EXECUTE FUNCTION "${flyway:defaultSchema}".avisar_cambio_residual();
CREATE TRIGGER avisar_version AFTER UPDATE OF numero_version ON "${flyway:defaultSchema}".implementaciones_control
    FOR EACH ROW WHEN (NEW.numero_version IS DISTINCT FROM OLD.numero_version)
    EXECUTE FUNCTION "${flyway:defaultSchema}".avisar_cambio_residual();
CREATE TRIGGER avisar_verificacion AFTER INSERT ON "${flyway:defaultSchema}".verificaciones_control
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".avisar_cambio_residual();

REVOKE ALL ON FUNCTION "${flyway:defaultSchema}".proteger_residual_confirmado(),
    "${flyway:defaultSchema}".exigir_residual_borrador(), "${flyway:defaultSchema}".avisar_cambio_residual() FROM PUBLIC;
GRANT SELECT, INSERT ON "${flyway:defaultSchema}".valoraciones_residuales,
    "${flyway:defaultSchema}".residual_verificaciones TO "${runtime_role}";
GRANT SELECT ON "${flyway:defaultSchema}".avisos_revision_residual TO "${runtime_role}";
GRANT UPDATE (residual_vigente_id) ON "${flyway:defaultSchema}".riesgos TO "${runtime_role}";
GRANT UPDATE (inherente_id,version_metodologia_id,probabilidad,impacto,periodo,fundamento,supuestos,fuente,sin_mitigacion,
    justificacion_sin_mitigacion,seleccion,probabilidad_indice,impacto_indice,nivel,confirmada_por,confirmada_en)
    ON "${flyway:defaultSchema}".valoraciones_residuales TO "${runtime_role}";
