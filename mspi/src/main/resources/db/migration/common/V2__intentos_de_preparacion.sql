CREATE TABLE "${flyway:defaultSchema}".intentos_preparacion (
    id uuid PRIMARY KEY,
    empresa_id uuid NOT NULL REFERENCES "${flyway:defaultSchema}".empresas(id),
    version_objetivo text NOT NULL,
    paso text NOT NULL CHECK (paso IN ('APROVISIONAR', 'CREDENCIALES', 'MIGRAR', 'VERIFICAR')),
    resultado text NOT NULL CHECK (resultado IN (
        'EN_CURSO', 'ESPERA_CREDENCIALES', 'FALLIDO', 'INTERRUMPIDO', 'VERIFICADO'
    )),
    iniciado_en timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    terminado_en timestamptz,
    CHECK ((resultado = 'EN_CURSO') = (terminado_en IS NULL))
);

CREATE INDEX intentos_preparacion_empresa
    ON "${flyway:defaultSchema}".intentos_preparacion (empresa_id, iniciado_en);

CREATE UNIQUE INDEX intentos_preparacion_uno_en_curso
    ON "${flyway:defaultSchema}".intentos_preparacion (empresa_id) WHERE resultado = 'EN_CURSO';
-- No se conceden privilegios al runtime común o empresarial.
