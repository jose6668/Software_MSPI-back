package software.mspi.acciones.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Authorized projection for the company results panel. A zero limit returns only aggregates. */
public interface ActionPanelProjection {
    Actions read(UUID actor, UUID company, LocalDate start, LocalDate end, int limit, long offset,
               ActionState state, Boolean overdue, LocalDate today);

    enum ActionState { PENDIENTE, EN_EJECUCION, EN_REVISION, COMPLETADA, CANCELADA }
    record Actions(long total, Map<ActionState, Long> porEstado, long vencidas, List<ActionRow> elementos) {}
    record ActionRow(UUID accionId, String codigo, ActionState estado, LocalDate fechaLimite, boolean vencida) {}
}
