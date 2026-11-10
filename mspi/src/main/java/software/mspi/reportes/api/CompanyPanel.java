package software.mspi.reportes.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import software.mspi.acciones.api.ActionPanelProjection.ActionState;
import software.mspi.acciones.api.ActionPanelProjection.Actions;
import software.mspi.evaluaciones.api.EvaluationPanelProjection.EvaluationState;
import software.mspi.evaluaciones.api.EvaluationPanelProjection.Evaluations;
import software.mspi.riesgos.api.InherentRisks.Impact;
import software.mspi.riesgos.api.InherentRisks.Level;
import software.mspi.riesgos.api.InherentRisks.Probability;
import software.mspi.riesgos.api.InherentRisks.State;
import software.mspi.riesgos.api.RiskPanelProjection.AssessmentStatus;
import software.mspi.riesgos.api.RiskPanelProjection.Basis;
import software.mspi.riesgos.api.RiskPanelProjection.Risks;

public interface CompanyPanel {
    Panel read(UUID actor, UUID company, Filters filters, Detail detail, int page, int size);

    enum Detail { EVALUACIONES, RIESGOS, ACCIONES }

    record Filters(LocalDate desde, LocalDate hasta, UUID evaluacionId, EvaluationState estadoEvaluacion,
                   State estadoRiesgo, Basis baseRiesgo, AssessmentStatus situacionRiesgo, Level nivelRiesgo,
                   Probability probabilidad, Impact impacto, ActionState estadoAccion, Boolean vencida) {}
    record Panel(UUID empresaId, String empresa, String zonaHoraria, Instant generadoEn, Filters filtros,
                 int pagina, int tamano, Detail detalle, Evaluations evaluaciones, Risks riesgos, Actions acciones) {}
}
