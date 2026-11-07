INSERT INTO comun.metodologias (id, codigo) VALUES
    ('86318f70-cf37-5cb9-b248-ce46c5ed0ac5', 'CID_MAX'),
    ('21a3c16b-2892-5d59-9f31-886decec7fa1', 'EVALUACION_CONTROLES');

INSERT INTO comun.versiones_metodologia (id, metodologia_id, version, identificador, nombre, fundamento, origen,
    reglas, aprobada_en, aprobada_por, publicada_en)
VALUES
    ('ebff20cd-27c7-5a49-8420-283b066a12f2', '86318f70-cf37-5cb9-b248-ce46c5ed0ac5', '1', 'CID_MAX_V1',
        'Criticidad de activos por máximo C/I/D',
        'El resultado se obtiene por el máximo de las tres dimensiones completas.',
        'APR-02 y CAL-ACT-01/02, documentación MSPI revisión 964249c',
        '{"dimensiones":["CONFIDENCIALIDAD","INTEGRIDAD","DISPONIBILIDAD"],"valores":[1,3,5],
          "niveles":{"1":"BAJA","3":"MEDIA","5":"ALTA"},"agregacion":"MAXIMO","faltantes":"PENDIENTE"}',
        DATE '2026-11-06', 'Usuario del proyecto (APR-02)', clock_timestamp()),
    ('2f11562f-a3f0-585a-b9eb-e0b7b058aef1', '21a3c16b-2892-5d59-9f31-886decec7fa1', '1', 'EVALUACION_CONTROLES_V1',
        'Escala y promedio de evaluación de controles',
        'Cada control aplicable evaluado tiene el mismo peso. El avance y las cláusulas se muestran por separado.',
        'APR-02 y CAL-EVA-01/02, documentación MSPI revisión 964249c',
        '{"calificaciones":[0,20,40,60,80,100],"agregacion":"PROMEDIO_CONTROLES_APLICABLES_EVALUADOS",
          "redondeoIntermedio":false,"avanceSeparado":true,"clausulasSeparadas":true}',
        DATE '2026-11-06', 'Usuario del proyecto (APR-02)', clock_timestamp());
