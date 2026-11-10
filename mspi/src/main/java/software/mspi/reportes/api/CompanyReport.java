package software.mspi.reportes.api;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import software.mspi.acciones.api.ActionPanelProjection.Actions;
import software.mspi.catalogos.api.ReferenceCatalogs.MethodReference;
import software.mspi.evaluaciones.api.EvaluationPanelProjection.Evaluations;
import software.mspi.reportes.api.CompanyPanel.Detail;
import software.mspi.reportes.api.CompanyPanel.Filters;
import software.mspi.riesgos.api.RiskPanelProjection.Risks;

public interface CompanyReport {
    Report generate(UUID actor, UUID company, Filters filters, Set<Detail> sections);

    record Report(UUID empresaId, String empresa, String zonaHoraria, Instant generadoEn, Filters filtros,
                  List<Detail> secciones, ReportEvaluations evaluaciones, ReportRisks riesgos, Actions acciones) {}
    record ReportEvaluations(Evaluations datos, List<MethodReference> metodologias) {}
    record ReportRisks(Risks datos, List<MethodReference> metodologias) {}
}
