ALTER TABLE "${flyway:defaultSchema}".hallazgos_evaluacion
    ADD UNIQUE (empresa_id,id);
ALTER TABLE "${flyway:defaultSchema}".evidencias
    ADD UNIQUE (empresa_id,id);

CREATE TABLE "${flyway:defaultSchema}".acciones_mejora (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL REFERENCES "${flyway:defaultSchema}".contexto_empresa(empresa_id),
    codigo varchar(80) NOT NULL CHECK (btrim(codigo) <> ''),
    riesgo_id uuid,
    hallazgo_id uuid,
    descripcion text NOT NULL CHECK (btrim(descripcion) <> ''),
    resultado_esperado text NOT NULL CHECK (btrim(resultado_esperado) <> ''),
    responsable_membresia_id uuid NOT NULL,
    fecha_limite date NOT NULL,
    evidencia_requerida boolean NOT NULL,
    descripcion_evidencia varchar(2000) NOT NULL CHECK (btrim(descripcion_evidencia) <> ''),
    justificacion_sin_evidencia varchar(2000),
    resumen_contexto_ra text NOT NULL CHECK (btrim(resumen_contexto_ra) <> ''),
    estado text NOT NULL CHECK (estado IN ('PENDIENTE','EN_EJECUCION','EN_REVISION','COMPLETADA','CANCELADA')),
    motivo_cancelacion varchar(2000),
    accion_anterior_id uuid,
    revision bigint NOT NULL CHECK (revision >= 0),
    motivo_cambio varchar(2000),
    creada_por uuid NOT NULL,
    creada_en timestamptz NOT NULL,
    UNIQUE (empresa_id,id),
    UNIQUE (empresa_id,codigo),
    FOREIGN KEY (empresa_id,riesgo_id) REFERENCES "${flyway:defaultSchema}".riesgos(empresa_id,id),
    FOREIGN KEY (empresa_id,hallazgo_id) REFERENCES "${flyway:defaultSchema}".hallazgos_evaluacion(empresa_id,id),
    FOREIGN KEY (empresa_id,responsable_membresia_id) REFERENCES comun.membresias(empresa_id,id),
    FOREIGN KEY (empresa_id,accion_anterior_id) REFERENCES "${flyway:defaultSchema}".acciones_mejora(empresa_id,id),
    CHECK ((riesgo_id IS NOT NULL) <> (hallazgo_id IS NOT NULL)),
    CHECK (accion_anterior_id IS NULL OR accion_anterior_id <> id),
    CHECK ((evidencia_requerida AND justificacion_sin_evidencia IS NULL)
        OR (NOT evidencia_requerida AND nullif(btrim(justificacion_sin_evidencia),'') IS NOT NULL)),
    CHECK ((estado = 'CANCELADA') = (motivo_cancelacion IS NOT NULL))
);
CREATE INDEX acciones_por_responsable ON "${flyway:defaultSchema}".acciones_mejora
    (empresa_id,responsable_membresia_id,estado,fecha_limite,id);
CREATE INDEX acciones_por_riesgo ON "${flyway:defaultSchema}".acciones_mejora (empresa_id,riesgo_id);
CREATE INDEX acciones_por_hallazgo ON "${flyway:defaultSchema}".acciones_mejora (empresa_id,hallazgo_id);

CREATE TABLE "${flyway:defaultSchema}".accion_evidencias_contexto (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL,
    accion_id uuid NOT NULL,
    evidencia_id uuid NOT NULL,
    motivo_contexto varchar(1000) NOT NULL CHECK (btrim(motivo_contexto) <> ''),
    creada_por uuid NOT NULL,
    creada_en timestamptz NOT NULL,
    UNIQUE (empresa_id,accion_id,evidencia_id),
    FOREIGN KEY (empresa_id,accion_id) REFERENCES "${flyway:defaultSchema}".acciones_mejora(empresa_id,id),
    FOREIGN KEY (empresa_id,evidencia_id) REFERENCES "${flyway:defaultSchema}".evidencias(empresa_id,id)
);
CREATE INDEX accion_contexto_por_evidencia ON "${flyway:defaultSchema}".accion_evidencias_contexto(empresa_id,evidencia_id);

CREATE TABLE "${flyway:defaultSchema}".eventos_accion (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL,
    accion_id uuid NOT NULL,
    tipo_evento text NOT NULL CHECK (tipo_evento IN ('CREADA','ACTUALIZADA','CANCELADA','AVANCE','SOLICITUD_REVISION')),
    comentario text,
    autor_id uuid NOT NULL,
    responsable_membresia_id uuid NOT NULL,
    creada_en timestamptz NOT NULL,
    FOREIGN KEY (empresa_id,accion_id) REFERENCES "${flyway:defaultSchema}".acciones_mejora(empresa_id,id),
    FOREIGN KEY (empresa_id,responsable_membresia_id) REFERENCES comun.membresias(empresa_id,id)
);
CREATE INDEX eventos_accion_por_fecha ON "${flyway:defaultSchema}".eventos_accion(empresa_id,accion_id,creada_en,id);

CREATE FUNCTION "${flyway:defaultSchema}".proteger_evento_accion()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    RAISE EXCEPTION 'El historial de acciones es append-only.' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER proteger_evento_accion BEFORE UPDATE OR DELETE ON "${flyway:defaultSchema}".eventos_accion
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".proteger_evento_accion();
REVOKE ALL ON FUNCTION "${flyway:defaultSchema}".proteger_evento_accion() FROM PUBLIC;

CREATE FUNCTION "${flyway:defaultSchema}".proteger_transicion_accion()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, pg_temp AS $$
BEGIN
    IF OLD.estado IN ('COMPLETADA','CANCELADA') THEN
        RAISE EXCEPTION 'Una acción terminal es inmutable.' USING ERRCODE = '23514';
    END IF;
    IF NEW.estado IS DISTINCT FROM OLD.estado AND NEW.estado <> 'CANCELADA' THEN
        RAISE EXCEPTION 'La transición de acción requiere el flujo de revisión.' USING ERRCODE = '23514';
    END IF;
    IF NEW.estado = 'CANCELADA' AND (NEW.motivo_cancelacion IS NULL OR btrim(NEW.motivo_cancelacion) = '') THEN
        RAISE EXCEPTION 'La cancelación requiere motivo.' USING ERRCODE = '23514';
    END IF;
    IF NEW.revision <> OLD.revision + 1 THEN
        RAISE EXCEPTION 'La revisión de acción debe avanzar una vez.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER proteger_transicion_accion BEFORE UPDATE ON "${flyway:defaultSchema}".acciones_mejora
    FOR EACH ROW EXECUTE FUNCTION "${flyway:defaultSchema}".proteger_transicion_accion();
REVOKE ALL ON FUNCTION "${flyway:defaultSchema}".proteger_transicion_accion() FROM PUBLIC;

GRANT SELECT,INSERT ON "${flyway:defaultSchema}".acciones_mejora,
    "${flyway:defaultSchema}".accion_evidencias_contexto,"${flyway:defaultSchema}".eventos_accion TO "${runtime_role}";
GRANT DELETE ON "${flyway:defaultSchema}".accion_evidencias_contexto TO "${runtime_role}";
GRANT UPDATE (riesgo_id,hallazgo_id,descripcion,resultado_esperado,responsable_membresia_id,fecha_limite,evidencia_requerida,
    descripcion_evidencia,justificacion_sin_evidencia,resumen_contexto_ra,estado,motivo_cancelacion,revision,motivo_cambio)
    ON "${flyway:defaultSchema}".acciones_mejora TO "${runtime_role}";
GRANT SELECT (id,empresa_id) ON comun.membresias TO "${runtime_role}";
