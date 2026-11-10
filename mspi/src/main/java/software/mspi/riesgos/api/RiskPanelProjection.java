package software.mspi.riesgos.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import software.mspi.riesgos.api.InherentRisks.Impact;
import software.mspi.riesgos.api.InherentRisks.Level;
import software.mspi.riesgos.api.InherentRisks.Probability;
import software.mspi.riesgos.api.InherentRisks.State;

/** Authorized projection for the company results panel. A zero limit returns only aggregates. */
public interface RiskPanelProjection {
    Projection read(UUID actor, UUID company, LocalDate start, LocalDate end, int limit, long offset,
                    Basis basis, State state, AssessmentStatus situation,
                    Level level, Probability probability, Impact impact, ZoneId zone, Instant now, boolean includeMethods);

    enum Basis { INHERENTE, RESIDUAL }
    enum AssessmentStatus { VIGENTE, PENDIENTE, REVISION_REQUERIDA }
    record Projection(Risks datos, List<UUID> versionesMetodologia) {}
    record Risks(long total, Map<State, Long> porEstado, Map<AssessmentStatus, Long> porSituacion,
                 Map<Level, Long> porNivel, List<Cell> matriz, List<RiskRow> elementos) {}
    record Cell(Probability probabilidad, Impact impacto, Level nivel, long total) {}
    record RiskRow(UUID riesgoId, String codigo, State estado, AssessmentStatus situacion, UUID valoracionId,
                   UUID versionMetodologiaId, Level nivel, Probability probabilidad, Impact impacto) {}
}
