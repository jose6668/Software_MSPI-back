CREATE TABLE comun.catalogos (
    id uuid PRIMARY KEY,
    codigo varchar(80) NOT NULL UNIQUE CHECK (btrim(codigo) <> '')
);

CREATE TABLE comun.versiones_catalogo (
    id uuid PRIMARY KEY,
    catalogo_id uuid NOT NULL REFERENCES comun.catalogos(id),
    version varchar(50) NOT NULL CHECK (btrim(version) <> ''),
    nombre varchar(150) NOT NULL CHECK (btrim(nombre) <> ''),
    origen varchar(1000) NOT NULL CHECK (btrim(origen) <> ''),
    huella_origen text CHECK (huella_origen ~ '^[a-f0-9]{64}$'),
    publicada_en timestamptz,
    retirada_en timestamptz,
    UNIQUE (catalogo_id, version),
    CHECK (retirada_en IS NULL OR (publicada_en IS NOT NULL AND retirada_en >= publicada_en))
);

CREATE TABLE comun.elementos_catalogo (
    id uuid PRIMARY KEY,
    version_catalogo_id uuid NOT NULL REFERENCES comun.versiones_catalogo(id),
    tipo text NOT NULL CHECK (tipo IN ('CONTROL', 'CLAUSULA')),
    codigo varchar(80) NOT NULL CHECK (btrim(codigo) <> ''),
    titulo varchar(250) NOT NULL CHECK (btrim(titulo) <> ''),
    descripcion text NOT NULL CHECK (btrim(descripcion) <> ''),
    grupo varchar(80) NOT NULL CHECK (btrim(grupo) <> ''),
    referencia_origen varchar(1000) NOT NULL CHECK (btrim(referencia_origen) <> ''),
    UNIQUE (version_catalogo_id, tipo, codigo),
    UNIQUE (version_catalogo_id, id)
);

CREATE TABLE comun.metodologias (
    id uuid PRIMARY KEY,
    codigo varchar(80) NOT NULL UNIQUE CHECK (btrim(codigo) <> '')
);

CREATE TABLE comun.versiones_metodologia (
    id uuid PRIMARY KEY,
    metodologia_id uuid NOT NULL REFERENCES comun.metodologias(id),
    version varchar(50) NOT NULL CHECK (btrim(version) <> ''),
    identificador varchar(80) NOT NULL UNIQUE CHECK (btrim(identificador) <> ''),
    nombre varchar(150) NOT NULL CHECK (btrim(nombre) <> ''),
    fundamento text NOT NULL CHECK (btrim(fundamento) <> ''),
    origen varchar(1000) NOT NULL CHECK (btrim(origen) <> ''),
    reglas jsonb NOT NULL CHECK (jsonb_typeof(reglas) = 'object' AND reglas <> '{}'::jsonb),
    aprobada_en date,
    aprobada_por text,
    publicada_en timestamptz,
    retirada_en timestamptz,
    UNIQUE (metodologia_id, version),
    CHECK ((aprobada_en IS NULL) = (aprobada_por IS NULL)),
    CHECK (publicada_en IS NULL OR (aprobada_en IS NOT NULL AND btrim(aprobada_por) <> '')),
    CHECK (retirada_en IS NULL OR (publicada_en IS NOT NULL AND retirada_en >= publicada_en))
);

-- Once published, a version can only be retired for new uses.
CREATE FUNCTION comun.proteger_edicion_publicada()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.publicada_en IS NULL THEN
        IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.retirada_en IS NULL AND NEW.retirada_en IS NOT NULL
            AND (to_jsonb(NEW) - 'retirada_en') = (to_jsonb(OLD) - 'retirada_en') THEN
            RETURN NEW;
        END IF;
    END IF;
    RAISE EXCEPTION 'Una edición publicada es inmutable.' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER proteger_version_catalogo BEFORE UPDATE OR DELETE ON comun.versiones_catalogo
    FOR EACH ROW EXECUTE FUNCTION comun.proteger_edicion_publicada();
CREATE TRIGGER proteger_version_metodologia BEFORE UPDATE OR DELETE ON comun.versiones_metodologia
    FOR EACH ROW EXECUTE FUNCTION comun.proteger_edicion_publicada();

CREATE FUNCTION comun.proteger_elemento_publicado()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    v_old uuid;
    v_new uuid;
BEGIN
    IF TG_OP <> 'INSERT' THEN v_old := OLD.version_catalogo_id; END IF;
    IF TG_OP <> 'DELETE' THEN v_new := NEW.version_catalogo_id; END IF;
    PERFORM 1 FROM comun.versiones_catalogo v
    WHERE v.id IN (v_old, v_new) ORDER BY v.id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM comun.versiones_catalogo v
               WHERE v.id IN (v_old, v_new) AND v.publicada_en IS NOT NULL) THEN
        RAISE EXCEPTION 'Los elementos de una edición publicada son inmutables.' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER proteger_elemento_catalogo BEFORE INSERT OR UPDATE OR DELETE ON comun.elementos_catalogo
    FOR EACH ROW EXECUTE FUNCTION comun.proteger_elemento_publicado();

CREATE FUNCTION comun.validar_publicacion_catalogo()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.publicada_en IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM comun.elementos_catalogo e WHERE e.version_catalogo_id = NEW.id
    ) THEN
        RAISE EXCEPTION 'Una edición requiere elementos antes de publicarse.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER validar_version_catalogo BEFORE INSERT OR UPDATE OF publicada_en ON comun.versiones_catalogo
    FOR EACH ROW EXECUTE FUNCTION comun.validar_publicacion_catalogo();

CREATE FUNCTION comun.proteger_codigo_referencia()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF NEW.codigo IS DISTINCT FROM OLD.codigo THEN
        RAISE EXCEPTION 'El código de referencia es inmutable.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER proteger_codigo_catalogo BEFORE UPDATE OF codigo ON comun.catalogos
    FOR EACH ROW EXECUTE FUNCTION comun.proteger_codigo_referencia();
CREATE TRIGGER proteger_codigo_metodologia BEFORE UPDATE OF codigo ON comun.metodologias
    FOR EACH ROW EXECUTE FUNCTION comun.proteger_codigo_referencia();

CREATE VIEW comun.catalogos_publicados AS
SELECT v.id AS version_id, c.codigo, v.version, v.nombre, v.origen, v.huella_origen,
       v.publicada_en, v.retirada_en IS NULL AS disponible_nuevos
FROM comun.versiones_catalogo v JOIN comun.catalogos c ON c.id = v.catalogo_id
WHERE v.publicada_en IS NOT NULL;

CREATE VIEW comun.elementos_catalogo_publicados AS
SELECT e.id, e.version_catalogo_id, e.tipo, e.codigo, e.titulo, e.descripcion, e.grupo, e.referencia_origen
FROM comun.elementos_catalogo e JOIN comun.versiones_catalogo v ON v.id = e.version_catalogo_id
WHERE v.publicada_en IS NOT NULL;

CREATE VIEW comun.metodologias_publicadas AS
SELECT v.id AS version_id, m.codigo, v.version, v.identificador, v.nombre, v.fundamento, v.origen,
       v.reglas, v.aprobada_en, v.aprobada_por, v.retirada_en IS NULL AS disponible_nuevos
FROM comun.versiones_metodologia v JOIN comun.metodologias m ON m.id = v.metodologia_id
WHERE v.publicada_en IS NOT NULL;

REVOKE ALL ON FUNCTION comun.proteger_edicion_publicada(), comun.proteger_elemento_publicado(),
    comun.validar_publicacion_catalogo(), comun.proteger_codigo_referencia() FROM PUBLIC;
GRANT SELECT ON comun.catalogos_publicados, comun.elementos_catalogo_publicados,
    comun.metodologias_publicadas TO "${runtime_role}";
