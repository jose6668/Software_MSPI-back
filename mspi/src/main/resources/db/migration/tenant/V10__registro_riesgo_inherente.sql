CREATE TABLE "${flyway:defaultSchema}".riesgos (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL REFERENCES "${flyway:defaultSchema}".contexto_empresa(empresa_id),
    codigo varchar(80) NOT NULL CHECK (btrim(codigo) <> ''),
    codigo_normalizado text COLLATE "C" NOT NULL CHECK (btrim(codigo_normalizado) <> ''),
    activo_principal_id uuid NOT NULL,
    amenaza varchar(1000) NOT NULL CHECK (btrim(amenaza) <> ''),
    vulnerabilidad varchar(1000) NOT NULL CHECK (btrim(vulnerabilidad) <> ''),
    descripcion text NOT NULL CHECK (btrim(descripcion) <> ''),
    responsable_id uuid NOT NULL,
    tratamiento_propuesto text CHECK (tratamiento_propuesto IN ('REDUCIR', 'COMPARTIR', 'ACEPTAR', 'EVITAR')),
    estado text NOT NULL CHECK (estado IN ('IDENTIFICADO', 'EN_ANALISIS')),
    revision bigint NOT NULL CHECK (revision >= 0),
    contexto bigint NOT NULL CHECK (contexto >= 0),
    inherente_vigente_id uuid,
    motivo_revision varchar(2000),
    creada_por uuid NOT NULL,
    creada_en timestamptz NOT NULL,
    UNIQUE (empresa_id, id),
    CONSTRAINT riesgo_codigo_local UNIQUE (empresa_id, codigo_normalizado),
    CHECK (inherente_vigente_id IS NULL OR estado = 'EN_ANALISIS'),
    CHECK (motivo_revision IS NULL OR (inherente_vigente_id IS NOT NULL AND btrim(motivo_revision) <> '')),
    FOREIGN KEY (empresa_id, activo_principal_id) REFERENCES "${flyway:defaultSchema}".activos(empresa_id, id),
    FOREIGN KEY (empresa_id, responsable_id) REFERENCES "${flyway:defaultSchema}".responsables_negocio(empresa_id, id)
);
CREATE INDEX riesgos_por_fecha ON "${flyway:defaultSchema}".riesgos (empresa_id, creada_en DESC, id);

CREATE TABLE "${flyway:defaultSchema}".valoraciones_inherentes (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL,
    riesgo_id uuid NOT NULL,
    version_metodologia_id uuid NOT NULL REFERENCES comun.versiones_metodologia(id),
    anterior_id uuid,
    motivo_reevaluacion varchar(2000),
    contexto bigint NOT NULL CHECK (contexto >= 0),
    probabilidad text CHECK (probabilidad IN ('MUY_BAJA', 'BAJA', 'MEDIA', 'ALTA', 'MUY_ALTA')),
    impacto text CHECK (impacto IN ('LEVE', 'MENOR', 'MODERADO', 'MAYOR', 'CATASTROFICO')),
    periodo varchar(150),
    fundamento text,
    supuestos text,
    fuente text,
    probabilidad_indice numeric(3,2),
    impacto_indice numeric(3,2),
    nivel text CHECK (nivel IN ('BAJO', 'MODERADO', 'ALTO', 'EXTREMO')),
    activo_base_id uuid,
    amenaza_base varchar(1000),
    vulnerabilidad_base varchar(1000),
    descripcion_base text,
    creada_por uuid NOT NULL,
    creada_en timestamptz NOT NULL,
    confirmada_por uuid,
    confirmada_en timestamptz,
    UNIQUE (empresa_id, riesgo_id, id),
    FOREIGN KEY (empresa_id, riesgo_id) REFERENCES "${flyway:defaultSchema}".riesgos(empresa_id, id),
    FOREIGN KEY (empresa_id, activo_base_id) REFERENCES "${flyway:defaultSchema}".activos(empresa_id, id),
    FOREIGN KEY (empresa_id, riesgo_id, anterior_id)
        REFERENCES "${flyway:defaultSchema}".valoraciones_inherentes(empresa_id, riesgo_id, id),
    CHECK (anterior_id IS NULL OR anterior_id <> id),
    CHECK ((anterior_id IS NULL AND motivo_reevaluacion IS NULL)
        OR (anterior_id IS NOT NULL AND motivo_reevaluacion IS NOT NULL AND btrim(motivo_reevaluacion) <> '')),
    CHECK ((confirmada_en IS NULL AND confirmada_por IS NULL AND nivel IS NULL
        AND probabilidad_indice IS NULL AND impacto_indice IS NULL AND activo_base_id IS NULL
        AND amenaza_base IS NULL AND vulnerabilidad_base IS NULL AND descripcion_base IS NULL)
        OR (confirmada_en IS NOT NULL AND confirmada_por IS NOT NULL AND nivel IS NOT NULL
        AND probabilidad_indice IS NOT NULL AND impacto_indice IS NOT NULL
        AND activo_base_id IS NOT NULL AND amenaza_base IS NOT NULL
        AND vulnerabilidad_base IS NOT NULL AND descripcion_base IS NOT NULL
        AND probabilidad IS NOT NULL AND impacto IS NOT NULL
        AND periodo IS NOT NULL AND btrim(periodo) <> ''
        AND fundamento IS NOT NULL AND btrim(fundamento) <> ''
        AND supuestos IS NOT NULL AND btrim(supuestos) <> ''
        AND fuente IS NOT NULL AND btrim(fuente) <> ''))
);
CREATE UNIQUE INDEX riesgo_un_borrador_inherente ON "${flyway:defaultSchema}".valoraciones_inherentes (empresa_id, riesgo_id)
    WHERE confirmada_en IS NULL;
CREATE INDEX inherentes_por_riesgo ON "${flyway:defaultSchema}".valoraciones_inherentes (empresa_id, riesgo_id, creada_en DESC, id);
ALTER TABLE "${flyway:defaultSchema}".riesgos
    ADD FOREIGN KEY (empresa_id, id, inherente_vigente_id)
    REFERENCES "${flyway:defaultSchema}".valoraciones_inherentes (empresa_id, riesgo_id, id);

CREATE TABLE "${flyway:defaultSchema}".riesgos_implementaciones (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL,
    riesgo_id uuid NOT NULL,
    implementacion_id uuid NOT NULL,
    efecto_esperado text NOT NULL CHECK (efecto_esperado IN ('PROBABILIDAD', 'IMPACTO', 'AMBOS')),
    justificacion text NOT NULL CHECK (btrim(justificacion) <> ''),
    creada_por uuid NOT NULL,
    creada_en timestamptz NOT NULL,
    CONSTRAINT riesgo_control_unico UNIQUE (empresa_id, riesgo_id, implementacion_id),
    FOREIGN KEY (empresa_id, riesgo_id) REFERENCES "${flyway:defaultSchema}".riesgos(empresa_id, id),
    FOREIGN KEY (empresa_id, implementacion_id)
        REFERENCES "${flyway:defaultSchema}".implementaciones_control(empresa_id, id)
);
CREATE INDEX riesgos_por_implementacion ON "${flyway:defaultSchema}".riesgos_implementaciones (empresa_id, implementacion_id);

CREATE FUNCTION "${flyway:defaultSchema}".proteger_inherente_confirmada()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.confirmada_en IS NOT NULL THEN
        RAISE EXCEPTION 'La valoración confirmada es inmutable.' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER proteger_inherente_confirmada BEFORE UPDATE OR DELETE ON "${flyway:defaultSchema}".valoraciones_inherentes
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".proteger_inherente_confirmada();
REVOKE ALL ON FUNCTION "${flyway:defaultSchema}".proteger_inherente_confirmada() FROM PUBLIC;

GRANT SELECT, INSERT ON "${flyway:defaultSchema}".riesgos,
    "${flyway:defaultSchema}".valoraciones_inherentes, "${flyway:defaultSchema}".riesgos_implementaciones TO "${runtime_role}";
GRANT UPDATE (activo_principal_id, amenaza, vulnerabilidad, descripcion, responsable_id, tratamiento_propuesto,
    estado, revision, contexto, inherente_vigente_id, motivo_revision) ON "${flyway:defaultSchema}".riesgos TO "${runtime_role}";
GRANT UPDATE (contexto, probabilidad, impacto, periodo, fundamento, supuestos, fuente, probabilidad_indice,
    impacto_indice, nivel, activo_base_id, amenaza_base, vulnerabilidad_base, descripcion_base,
    confirmada_por, confirmada_en) ON "${flyway:defaultSchema}".valoraciones_inherentes TO "${runtime_role}";
