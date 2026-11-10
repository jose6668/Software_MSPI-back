package software.mspi.riesgos.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface InherentRisks {
    Page<Risk> list(Scope scope, String code, State state, int page, int size);
    Risk get(Scope scope, UUID id);
    Risk create(Scope scope, String code, Details details);
    Risk edit(Scope scope, UUID id, long revision, Details details, String reason);
    Risk startAnalysis(Scope scope, UUID id, long revision);
    Page<Assessment> assessments(Scope scope, UUID id, int page, int size);
    Assessment startAssessment(Scope scope, UUID id, long revision, Inputs inputs, String reason);
    Assessment editAssessment(Scope scope, UUID id, UUID assessment, long revision, Inputs inputs);
    Assessment confirmAssessment(Scope scope, UUID id, UUID assessment, long revision);
    Page<Link> links(Scope scope, UUID id, int page, int size);
    Link link(Scope scope, UUID id, long revision, UUID implementation, Effect effect, String rationale);
    Risk createRelated(Scope scope, UUID original, String code, Details details);
    Risk transition(Scope scope, UUID id, long revision, State destination, String reason, Map<UUID, String> cancellations);
    Acceptance accept(Scope scope, UUID id, long revision, String reason, LocalDate reviewDate);
    Page<Acceptance> acceptances(Scope scope, UUID id, int page, int size);

    enum State { IDENTIFICADO, EN_ANALISIS, EN_TRATAMIENTO, EN_SEGUIMIENTO, CERRADO }
    enum Treatment { REDUCIR, COMPARTIR, ACEPTAR, EVITAR }
    enum Probability { MUY_BAJA, BAJA, MEDIA, ALTA, MUY_ALTA }
    enum Impact { LEVE, MENOR, MODERADO, MAYOR, CATASTROFICO }
    enum Level { BAJO, MODERADO, ALTO, EXTREMO }
    enum Effect { PROBABILIDAD, IMPACTO, AMBOS }

    record Scope(UUID actor, UUID company, UUID correlation) {}
    record Page<T>(List<T> elementos, int pagina, int tamano, boolean hayMas) {}
    record Details(UUID activoPrincipalId, String amenaza, String vulnerabilidad, String descripcion,
                   UUID responsableId, Treatment tratamientoPropuesto) {}
    record Risk(UUID riesgoId, String codigo, Details detalle, State estado, long revision,
                UUID inherenteVigenteId, Level nivelInherente, boolean requiereRevision, String motivoRevision,
                UUID creadaPor, Instant creadaEn, UUID riesgoRelacionadoId, Closure cierre) {}
    record Closure(String justificacion, UUID cerradaPor, Instant cerradaEn,
                   UUID inherenteId, UUID residualId, UUID aceptacionId, Map<String, String> accionesCanceladas) {}
    record Acceptance(UUID aceptacionId, UUID riesgoId, UUID inherenteId, UUID residualId,
                      String justificacion, LocalDate fechaProximaRevision, UUID aprobadaPor, Instant aprobadaEn,
                      String estado, long revisionRiesgo) {}
    record Inputs(Probability probabilidad, Impact impacto, String periodoReferencia, String fundamento, String supuestos,
                  String fuente) {}
    record Assessment(UUID valoracionId, UUID riesgoId, UUID versionMetodologiaId, UUID valoracionAnteriorId,
                      String motivoReevaluacion, long revisionRiesgo, Inputs entradas, String estadoResultado,
                      Level nivel, BigDecimal probabilidadIndice, BigDecimal impactoIndice, boolean requiereRevision, Base base,
                      UUID creadaPor, Instant creadaEn, UUID confirmadaPor, Instant confirmadaEn) {}
    record Base(UUID activoPrincipalId, String amenaza, String vulnerabilidad, String descripcion) {}
    record Link(UUID vinculoId, UUID implementacionId, Effect efectoEsperado, String justificacion, long revisionRiesgo,
                UUID creadaPor, Instant creadaEn) {}
}
