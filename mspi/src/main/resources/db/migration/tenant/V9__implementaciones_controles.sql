CREATE TABLE "${flyway:defaultSchema}".implementaciones_control (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL REFERENCES "${flyway:defaultSchema}".contexto_empresa(empresa_id),
    version_catalogo_id uuid NOT NULL,
    elemento_id uuid NOT NULL,
    estado text NOT NULL CHECK (estado IN ('PROPUESTO', 'EN_IMPLEMENTACION', 'IMPLEMENTADO')),
    numero_version integer NOT NULL CHECK (numero_version > 0),
    revision bigint NOT NULL CHECK (revision >= 0),
    creada_por uuid NOT NULL,
    creada_en timestamptz NOT NULL,
    UNIQUE (empresa_id, id),
    FOREIGN KEY (version_catalogo_id, elemento_id) REFERENCES comun.elementos_catalogo(version_catalogo_id, id)
);

CREATE TABLE "${flyway:defaultSchema}".versiones_implementacion_control (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL,
    implementacion_id uuid NOT NULL,
    numero integer NOT NULL CHECK (numero > 0),
    nombre varchar(250) NOT NULL CHECK (btrim(nombre) <> ''),
    descripcion text NOT NULL CHECK (btrim(descripcion) <> ''),
    alcance text NOT NULL CHECK (btrim(alcance) <> ''),
    trabajo text NOT NULL CHECK (btrim(trabajo) <> ''),
    responsable_id uuid NOT NULL,
    tipo text CHECK (tipo IN ('PREVENTIVO', 'DETECTIVO', 'CORRECTIVO')),
    modo text CHECK (modo IN ('MANUAL', 'AUTOMATICO')),
    motivo_cambio varchar(2000),
    creada_por uuid NOT NULL,
    creada_en timestamptz NOT NULL,
    FOREIGN KEY (empresa_id, implementacion_id)
        REFERENCES "${flyway:defaultSchema}".implementaciones_control(empresa_id, id),
    FOREIGN KEY (empresa_id, responsable_id)
        REFERENCES "${flyway:defaultSchema}".responsables_negocio(empresa_id, id),
    UNIQUE (empresa_id, implementacion_id, numero),
    UNIQUE (empresa_id, implementacion_id, id),
    CHECK ((numero = 1 AND motivo_cambio IS NULL)
        OR (numero > 1 AND motivo_cambio IS NOT NULL AND btrim(motivo_cambio) <> ''))
);

ALTER TABLE "${flyway:defaultSchema}".implementaciones_control
    ADD FOREIGN KEY (empresa_id, id, numero_version)
    REFERENCES "${flyway:defaultSchema}".versiones_implementacion_control(empresa_id, implementacion_id, numero)
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE "${flyway:defaultSchema}".evidencias
    ALTER COLUMN evaluacion_id DROP NOT NULL,
    ALTER COLUMN respuesta_id DROP NOT NULL,
    ADD COLUMN implementacion_id uuid,
    ADD COLUMN version_implementacion_id uuid,
    ADD CHECK ((evaluacion_id IS NOT NULL AND respuesta_id IS NOT NULL
                    AND implementacion_id IS NULL AND version_implementacion_id IS NULL)
        OR (evaluacion_id IS NULL AND respuesta_id IS NULL
                    AND implementacion_id IS NOT NULL AND version_implementacion_id IS NOT NULL)),
    ADD FOREIGN KEY (empresa_id, implementacion_id, version_implementacion_id)
        REFERENCES "${flyway:defaultSchema}".versiones_implementacion_control(empresa_id, implementacion_id, id),
    ADD UNIQUE (empresa_id, implementacion_id, version_implementacion_id, id);

CREATE INDEX evidencias_por_version_control ON "${flyway:defaultSchema}".evidencias
    (empresa_id, implementacion_id, version_implementacion_id, creada_en, id)
    WHERE estado_carga = 'DISPONIBLE';

CREATE TABLE "${flyway:defaultSchema}".aplicaciones_control (
    version_id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL,
    implementacion_id uuid NOT NULL,
    fecha_aplicacion date NOT NULL,
    evidencia_id uuid NOT NULL,
    huella char(64) NOT NULL,
    registrada_por uuid NOT NULL,
    registrada_en timestamptz NOT NULL,
    FOREIGN KEY (empresa_id, implementacion_id, version_id, evidencia_id)
        REFERENCES "${flyway:defaultSchema}".evidencias(empresa_id, implementacion_id, version_implementacion_id, id)
);

CREATE TABLE "${flyway:defaultSchema}".verificaciones_control (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL,
    implementacion_id uuid NOT NULL,
    version_id uuid NOT NULL REFERENCES "${flyway:defaultSchema}".aplicaciones_control(version_id),
    revision bigint NOT NULL CHECK (revision > 0),
    prueba text NOT NULL CHECK (btrim(prueba) <> ''),
    fundamento text NOT NULL CHECK (btrim(fundamento) <> ''),
    criterios_evaluados integer NOT NULL CHECK (criterios_evaluados > 0),
    criterios_cumplidos integer NOT NULL CHECK (criterios_cumplidos BETWEEN 0 AND criterios_evaluados),
    fecha_verificacion date NOT NULL,
    proxima_revision date NOT NULL CHECK (proxima_revision > fecha_verificacion),
    evidencia_id uuid NOT NULL,
    huella char(64) NOT NULL,
    verificada_por uuid NOT NULL,
    verificada_en timestamptz NOT NULL,
    FOREIGN KEY (empresa_id, implementacion_id, version_id, evidencia_id)
        REFERENCES "${flyway:defaultSchema}".evidencias(empresa_id, implementacion_id, version_implementacion_id, id),
    UNIQUE (version_id, revision),
    CONSTRAINT verificacion_control_evidencia_nueva UNIQUE (version_id, evidencia_id)
);

CREATE FUNCTION "${flyway:defaultSchema}".validar_estado_implementacion()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.estado = 'PROPUESTO' AND NEW.numero_version = 1 AND NEW.revision = 0 THEN RETURN NEW; END IF;
    ELSIF NEW.revision = OLD.revision + 1 AND (
        (OLD.estado = NEW.estado AND OLD.numero_version = NEW.numero_version AND OLD.estado <> 'PROPUESTO')
        OR (OLD.estado = 'PROPUESTO' AND NEW.estado = 'EN_IMPLEMENTACION' AND OLD.numero_version = NEW.numero_version)
        OR (OLD.estado = 'IMPLEMENTADO' AND NEW.estado = 'EN_IMPLEMENTACION' AND NEW.numero_version = OLD.numero_version + 1)
        OR (OLD.estado = 'EN_IMPLEMENTACION' AND NEW.estado = 'IMPLEMENTADO' AND OLD.numero_version = NEW.numero_version
            AND EXISTS (
                SELECT 1 FROM "${flyway:defaultSchema}".aplicaciones_control a
                JOIN "${flyway:defaultSchema}".versiones_implementacion_control v ON v.id = a.version_id
                WHERE v.empresa_id = NEW.empresa_id AND v.implementacion_id = NEW.id AND v.numero = NEW.numero_version
            ))
    ) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'Transición de implementación inválida.' USING ERRCODE = 'P1902';
END;
$$;
CREATE TRIGGER validar_estado_implementacion BEFORE INSERT OR UPDATE ON "${flyway:defaultSchema}".implementaciones_control
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".validar_estado_implementacion();

CREATE FUNCTION "${flyway:defaultSchema}".exigir_evidencia_control_vigente()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM 1 FROM "${flyway:defaultSchema}".implementaciones_control i
    JOIN "${flyway:defaultSchema}".versiones_implementacion_control v
      ON (v.empresa_id, v.implementacion_id, v.numero) = (i.empresa_id, i.id, i.numero_version)
    WHERE i.empresa_id = NEW.empresa_id AND i.id = NEW.implementacion_id AND v.id = NEW.version_id
      AND i.estado = CASE WHEN TG_TABLE_NAME = 'aplicaciones_control' THEN 'EN_IMPLEMENTACION' ELSE 'IMPLEMENTADO' END
    FOR SHARE OF i;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'La implementación no admite esta operación.' USING ERRCODE = 'P1902';
    END IF;
    PERFORM 1 FROM "${flyway:defaultSchema}".evidencias e
    WHERE e.empresa_id = NEW.empresa_id AND e.implementacion_id = NEW.implementacion_id
      AND e.version_implementacion_id = NEW.version_id AND e.id = NEW.evidencia_id
      AND e.estado_carga = 'DISPONIBLE' AND e.estado_inspeccion = 'LIMPIA'
      AND e.huella_contenido = NEW.huella
    FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'La evidencia de control no está disponible.' USING ERRCODE = 'P1901';
    END IF;
    IF TG_TABLE_NAME = 'verificaciones_control' THEN
        PERFORM 1 FROM "${flyway:defaultSchema}".aplicaciones_control a
        JOIN "${flyway:defaultSchema}".evidencias e ON e.id = a.evidencia_id
        WHERE a.version_id = NEW.version_id AND e.estado_inspeccion <> 'BLOQUEADA'
        FOR SHARE OF e;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'La evidencia de aplicación no está disponible.' USING ERRCODE = 'P1901';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER exigir_evidencia_aplicacion BEFORE INSERT ON "${flyway:defaultSchema}".aplicaciones_control
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".exigir_evidencia_control_vigente();
CREATE TRIGGER exigir_evidencia_verificacion BEFORE INSERT ON "${flyway:defaultSchema}".verificaciones_control
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".exigir_evidencia_control_vigente();

DROP TRIGGER exigir_borrador_evidencia ON "${flyway:defaultSchema}".evidencias;
CREATE TRIGGER exigir_borrador_evidencia BEFORE INSERT OR UPDATE OF estado_carga
    ON "${flyway:defaultSchema}".evidencias FOR EACH ROW WHEN (NEW.evaluacion_id IS NOT NULL)
    EXECUTE FUNCTION "${flyway:defaultSchema}".exigir_evaluacion_borrador();

CREATE FUNCTION "${flyway:defaultSchema}".exigir_version_control_actual()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    PERFORM 1 FROM "${flyway:defaultSchema}".implementaciones_control i
    JOIN "${flyway:defaultSchema}".versiones_implementacion_control v
      ON (v.empresa_id, v.implementacion_id, v.numero) = (i.empresa_id, i.id, i.numero_version)
    WHERE i.empresa_id = NEW.empresa_id AND i.id = NEW.implementacion_id
      AND v.id = NEW.version_implementacion_id AND i.estado <> 'PROPUESTO'
    FOR SHARE OF i;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'La versión de control no admite nuevas cargas.' USING ERRCODE = 'P1902';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER exigir_version_control_actual BEFORE INSERT OR UPDATE OF estado_carga
    ON "${flyway:defaultSchema}".evidencias FOR EACH ROW WHEN (NEW.implementacion_id IS NOT NULL)
    EXECUTE FUNCTION "${flyway:defaultSchema}".exigir_version_control_actual();

REVOKE ALL ON FUNCTION "${flyway:defaultSchema}".exigir_evidencia_control_vigente(),
    "${flyway:defaultSchema}".exigir_version_control_actual(),
    "${flyway:defaultSchema}".validar_estado_implementacion() FROM PUBLIC;
GRANT SELECT, INSERT ON "${flyway:defaultSchema}".implementaciones_control,
    "${flyway:defaultSchema}".versiones_implementacion_control,
    "${flyway:defaultSchema}".aplicaciones_control, "${flyway:defaultSchema}".verificaciones_control TO "${runtime_role}";
GRANT UPDATE (estado, numero_version, revision) ON "${flyway:defaultSchema}".implementaciones_control TO "${runtime_role}";
GRANT INSERT (implementacion_id, version_implementacion_id)
    ON "${flyway:defaultSchema}".evidencias TO "${runtime_role}";
