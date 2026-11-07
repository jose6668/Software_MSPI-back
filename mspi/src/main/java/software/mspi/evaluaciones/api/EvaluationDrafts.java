package software.mspi.evaluaciones.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface EvaluationDrafts {

    Page<Evaluation> list(Scope scope, int page, int size);
    Evaluation get(Scope scope, UUID evaluation);
    Evaluation create(Scope scope, Create input);
    Page<Response> responses(Scope scope, UUID evaluation, ElementType type, String group, int page, int size);
    Response updateResponse(Scope scope, UUID evaluation, UUID response, long revision, Answer input);
    Results results(Scope scope, UUID evaluation);
    Page<Finding> findings(Scope scope, UUID evaluation, UUID response, int page, int size);
    Finding createFinding(Scope scope, UUID evaluation, UUID response, long revision, Gap input);
    Finding updateFinding(Scope scope, UUID evaluation, UUID response, UUID finding, long revision, Gap input);

    void requireReadableResponse(Scope scope, UUID evaluation, UUID response);

    /** Locks and checks the draft before publication. An empty result leaves the evaluation revision unchanged. */
    <T> Optional<T> attachEvidence(Scope scope, UUID evaluation, UUID response, long revision,
                                  Supplier<Optional<T>> publish);

    enum ElementType { CONTROL, CLAUSULA }
    enum Applicability { SIN_DECIDIR, APLICA, NO_APLICA }
    enum ResultStatus { SIN_ELEMENTOS, NO_APLICA, PENDIENTE, PROVISIONAL, CALCULADO }

    record Scope(UUID actor, UUID company, UUID correlation) {}
    record Page<T>(List<T> elementos, int pagina, int tamano, boolean hayMas) {}

    record Create(String nombre, LocalDate fechaEvaluacion, String alcance, UUID responsableId,
                  UUID versionCatalogoId, UUID versionMetodologiaId) {}

    record Evaluation(UUID evaluacionId, String nombre, LocalDate fechaEvaluacion, String alcance, UUID responsableId,
                      UUID versionCatalogoId, UUID versionMetodologiaId, String estado, long revision,
                      UUID creadaPor, Instant creadaEn, Instant actualizadaEn) {}

    record Answer(Applicability aplicabilidad, String justificacionAplicabilidad, Integer calificacion, String fundamento) {}

    record Response(UUID respuestaId, UUID elementoId, UUID versionCatalogoId, ElementType tipo, String codigo,
                    String titulo, String grupo, Answer respuesta, long revisionEvaluacion,
                    UUID actualizadaPor, Instant actualizadaEn) {}

    record Gap(String descripcionBrecha, String recomendacion) {}

    record Finding(UUID hallazgoId, UUID respuestaId, Gap hallazgo, long revisionEvaluacion,
                   UUID creadaPor, Instant creadaEn, UUID actualizadaPor, Instant actualizadaEn) {}

    record Totals(long total, long aplicables, long evaluados, long pendientes, long sinDecidir, long noAplican,
                  BigDecimal puntaje, BigDecimal avance, ResultStatus estado) {}

    record GroupResult(String grupo, Totals resultado) {}
    record Results(UUID evaluacionId, long revision, Totals controlesGlobal,
                   List<GroupResult> dominios, List<GroupResult> clausulas) {}
}
