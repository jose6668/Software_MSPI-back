CREATE TABLE "${flyway:defaultSchema}".valoraciones_activo (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL REFERENCES "${flyway:defaultSchema}".contexto_empresa(empresa_id),
    activo_id uuid NOT NULL,
    numero integer NOT NULL CHECK (numero > 0),
    revision bigint NOT NULL CHECK (revision >= 0),
    metodologia text NOT NULL CHECK (metodologia = 'CID_MAX_V1'),
    version_tipos text NOT NULL CHECK (version_tipos = 'DATOS_PERSONALES_V1'),
    confidencialidad integer CHECK (confidencialidad IN (1, 3, 5)),
    integridad integer CHECK (integridad IN (1, 3, 5)),
    disponibilidad integer CHECK (disponibilidad IN (1, 3, 5)),
    fundamento text,
    datos_personales boolean,
    datos_menores boolean,
    finalidad text,
    autorizacion text CHECK (autorizacion IN ('SI', 'NO', 'NO_APLICA')),
    justificacion varchar(2000),
    motivo_revision varchar(1000),
    criticidad integer,
    creada_por uuid NOT NULL,
    creada_en timestamptz NOT NULL,
    actualizada_por uuid NOT NULL,
    actualizada_en timestamptz NOT NULL,
    confirmada_por uuid,
    confirmada_en timestamptz,
    FOREIGN KEY (empresa_id, activo_id) REFERENCES "${flyway:defaultSchema}".activos(empresa_id, id),
    UNIQUE (empresa_id, id),
    UNIQUE (empresa_id, activo_id, id),
    UNIQUE (empresa_id, activo_id, numero),
    CHECK (numero = 1 OR (motivo_revision IS NOT NULL AND btrim(motivo_revision) <> '')),
    CHECK (datos_personales IS TRUE OR
        (datos_menores IS NOT TRUE AND finalidad IS NULL AND autorizacion IS NULL AND justificacion IS NULL)),
    CHECK (autorizacion IS DISTINCT FROM 'NO_APLICA' OR (justificacion IS NOT NULL AND btrim(justificacion) <> '')),
    CHECK (justificacion IS NULL OR autorizacion IS NOT DISTINCT FROM 'NO_APLICA'),
    CHECK ((confirmada_en IS NULL) = (confirmada_por IS NULL)),
    CHECK ((confirmada_en IS NULL AND criticidad IS NULL) OR
        (confirmada_en IS NOT NULL AND confidencialidad IS NOT NULL AND integridad IS NOT NULL
        AND disponibilidad IS NOT NULL AND criticidad IS NOT NULL
        AND criticidad = greatest(confidencialidad, integridad, disponibilidad)
        AND fundamento IS NOT NULL AND btrim(fundamento) <> '' AND datos_personales IS NOT NULL
        AND (NOT datos_personales OR (datos_menores IS NOT NULL AND finalidad IS NOT NULL
            AND btrim(finalidad) <> '' AND autorizacion IS NOT NULL))))
);

CREATE UNIQUE INDEX una_valoracion_pendiente_por_activo
    ON "${flyway:defaultSchema}".valoraciones_activo (empresa_id, activo_id) WHERE confirmada_en IS NULL;

CREATE TABLE "${flyway:defaultSchema}".valoracion_tipos_dato (
    empresa_id uuid NOT NULL DEFAULT '${empresa_id}'::uuid
        REFERENCES "${flyway:defaultSchema}".contexto_empresa(empresa_id),
    valoracion_id uuid NOT NULL,
    tipo_codigo text NOT NULL CHECK (tipo_codigo IN ('IDENTIFICACION', 'CONTACTO', 'FINANCIEROS',
                                                      'SALUD', 'BIOMETRICOS', 'OTROS')),
    PRIMARY KEY (valoracion_id, tipo_codigo),
    FOREIGN KEY (empresa_id, valoracion_id) REFERENCES "${flyway:defaultSchema}".valoraciones_activo(empresa_id, id)
);

ALTER TABLE "${flyway:defaultSchema}".activos ADD COLUMN valoracion_vigente_id uuid,
    ADD CONSTRAINT activo_valoracion_local FOREIGN KEY (empresa_id, id, valoracion_vigente_id)
        REFERENCES "${flyway:defaultSchema}".valoraciones_activo(empresa_id, activo_id, id);

CREATE FUNCTION "${flyway:defaultSchema}".proteger_valoracion_activo()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.confirmada_en IS NOT NULL THEN
        RAISE EXCEPTION 'La valoración confirmada es inmutable.' USING ERRCODE = '23514';
    END IF;
    IF NEW.confirmada_en IS NOT NULL AND NEW.datos_personales IS DISTINCT FROM EXISTS (
        SELECT 1 FROM "${flyway:defaultSchema}".valoracion_tipos_dato t WHERE t.valoracion_id = NEW.id
    ) THEN
        RAISE EXCEPTION 'Tipos de datos personales inconsistentes.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER proteger_valoracion_activo BEFORE INSERT OR UPDATE ON "${flyway:defaultSchema}".valoraciones_activo
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".proteger_valoracion_activo();

CREATE FUNCTION "${flyway:defaultSchema}".proteger_tipos_valoracion()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_id uuid := CASE WHEN TG_OP = 'DELETE' THEN OLD.valoracion_id ELSE NEW.valoracion_id END;
    v_confirmada timestamptz;
    v_personales boolean;
BEGIN
    SELECT v.confirmada_en, v.datos_personales INTO v_confirmada, v_personales
    FROM "${flyway:defaultSchema}".valoraciones_activo v WHERE v.id = v_id FOR UPDATE;
    IF v_confirmada IS NOT NULL THEN
        RAISE EXCEPTION 'Los tipos de una valoración confirmada son inmutables.' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    IF v_personales IS NOT TRUE THEN
        RAISE EXCEPTION 'El tipo requiere que se registren datos personales.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER proteger_tipos_valoracion BEFORE INSERT OR DELETE ON "${flyway:defaultSchema}".valoracion_tipos_dato
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".proteger_tipos_valoracion();

CREATE FUNCTION "${flyway:defaultSchema}".validar_valoracion_vigente()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.valoracion_vigente_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM "${flyway:defaultSchema}".valoraciones_activo v
        WHERE v.empresa_id = NEW.empresa_id AND v.activo_id = NEW.id
          AND v.id = NEW.valoracion_vigente_id AND v.confirmada_en IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'La valoración vigente debe estar confirmada y pertenecer al activo.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER validar_valoracion_vigente BEFORE INSERT OR UPDATE OF valoracion_vigente_id
    ON "${flyway:defaultSchema}".activos FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".validar_valoracion_vigente();

REVOKE ALL ON FUNCTION "${flyway:defaultSchema}".proteger_valoracion_activo(),
    "${flyway:defaultSchema}".proteger_tipos_valoracion(), "${flyway:defaultSchema}".validar_valoracion_vigente()
    FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON "${flyway:defaultSchema}".valoraciones_activo TO "${runtime_role}";
GRANT SELECT, INSERT, DELETE ON "${flyway:defaultSchema}".valoracion_tipos_dato TO "${runtime_role}";
GRANT UPDATE (valoracion_vigente_id) ON "${flyway:defaultSchema}".activos TO "${runtime_role}";
