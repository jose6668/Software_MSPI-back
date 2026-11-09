INSERT INTO comun.metodologias(id, codigo)
VALUES ('e5e639c5-99b8-4c3c-8a0d-5d812ddfeb36', 'RIESGO_CATEGORICO');
INSERT INTO comun.versiones_metodologia(id, metodologia_id, version, identificador, nombre, fundamento, origen,
    reglas, aprobada_en, aprobada_por, publicada_en)
VALUES ('4cbd3e6a-0382-4a97-b090-d68fe1105320', 'e5e639c5-99b8-4c3c-8a0d-5d812ddfeb36', '1',
    'RIESGO_CATEGORICO_V1', 'Valoración categórica inicial de riesgos',
    'Selección de categorías con fundamento, periodo, fuente y supuestos. El nivel proviene de la matriz.',
    'APR-02, CAL-RIE-01/03 y rúbrica cualitativa inicial aprobada en HU-020',
    '{
      "probabilidad":{
        "MUY_BAJA":{"indice":0.20,"criterio":"Excepcional en el periodo, con exposición y antecedentes muy limitados."},
        "BAJA":{"indice":0.40,"criterio":"Poco probable en el periodo, con exposición limitada o antecedentes aislados."},
        "MEDIA":{"indice":0.60,"criterio":"Plausible en el periodo, con exposición apreciable o antecedentes relevantes."},
        "ALTA":{"indice":0.80,"criterio":"Probable en el periodo, con exposición frecuente o antecedentes repetidos."},
        "MUY_ALTA":{"indice":1.00,"criterio":"Esperada en el periodo, con exposición persistente o antecedentes recurrentes."}
      },
      "impacto":{
        "LEVE":{"indice":0.20,"criterio":"Afectación localizada y recuperación sencilla."},
        "MENOR":{"indice":0.40,"criterio":"Afectación limitada a un área o servicio, con recuperación manejable."},
        "MODERADO":{"indice":0.60,"criterio":"Afectación significativa a operaciones, con recuperación que requiere coordinación."},
        "MAYOR":{"indice":0.80,"criterio":"Afectación grave de servicios o áreas relevantes, con recuperación compleja o prolongada."},
        "CATASTROFICO":{"indice":1.00,"criterio":"Afectación crítica o generalizada, con recuperación muy difícil o consecuencias irreversibles."}
      },
      "matriz":{
        "MUY_BAJA":{"LEVE":"BAJO","MENOR":"BAJO","MODERADO":"MODERADO","MAYOR":"ALTO","CATASTROFICO":"EXTREMO"},
        "BAJA":{"LEVE":"BAJO","MENOR":"MODERADO","MODERADO":"MODERADO","MAYOR":"ALTO","CATASTROFICO":"EXTREMO"},
        "MEDIA":{"LEVE":"MODERADO","MENOR":"MODERADO","MODERADO":"MODERADO","MAYOR":"ALTO","CATASTROFICO":"EXTREMO"},
        "ALTA":{"LEVE":"MODERADO","MENOR":"MODERADO","MODERADO":"ALTO","MAYOR":"ALTO","CATASTROFICO":"EXTREMO"},
        "MUY_ALTA":{"LEVE":"ALTO","MENOR":"ALTO","MODERADO":"ALTO","MAYOR":"ALTO","CATASTROFICO":"EXTREMO"}
      },
      "seleccion":"CATEGORICA_DOCUMENTADA","faltantes":"PENDIENTE"
    }', DATE '2026-11-09', 'Usuario del proyecto (HU-020)', clock_timestamp());
