package software.mspi.evaluaciones.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Authorized projection for the company results panel. A zero limit returns only aggregates. */
public interface EvaluationPanelProjection {
    Projection read(UUID actor, UUID company, LocalDate start, LocalDate end, int limit, long offset,
                    UUID evaluation, EvaluationState state, boolean includeMethods);

    enum EvaluationState { BORRADOR, EN_REVISION, CERRADA }
    record Projection(Evaluations datos, List<UUID> versionesMetodologia) {}
    record Evaluations(long total, Map<EvaluationState, Long> porEstado, List<EvaluationRow> elementos) {}
    record EvaluationRow(UUID evaluacionId, String nombre, LocalDate fecha, EvaluationState estado,
                         UUID versionCatalogoId, UUID versionMetodologiaId, long sinDecidir,
                         long sinCalificacion, long heredadasPendientes, long controlesAplicables, long controlesEvaluados,
                         long clausulasAplicables, long clausulasEvaluadas) {}
}
