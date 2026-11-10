package software.mspi.reportes.internal;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.mspi.acciones.api.ActionPanelProjection;
import software.mspi.catalogos.api.ReferenceCatalogs;
import software.mspi.catalogos.api.ReferenceCatalogs.MethodReference;
import software.mspi.empresas.api.CompanyAccess;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;
import software.mspi.evaluaciones.api.EvaluationPanelProjection;
import software.mspi.reportes.api.CompanyPanel;
import software.mspi.reportes.api.CompanyReport;
import software.mspi.riesgos.api.RiskPanelProjection;

@Service
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
final class DefaultCompanyPanel implements CompanyPanel, CompanyReport {
    private final TenantUnitOfWork work;
    private final CompanyAccess access;
    private final EvaluationPanelProjection evaluations;
    private final RiskPanelProjection risks;
    private final ActionPanelProjection actions;
    private final ReferenceCatalogs catalogs;

    DefaultCompanyPanel(TenantUnitOfWork work, CompanyAccess access, EvaluationPanelProjection evaluations,
                        RiskPanelProjection risks, ActionPanelProjection actions, ReferenceCatalogs catalogs) {
        this.work = work;
        this.access = access;
        this.evaluations = evaluations;
        this.risks = risks;
        this.actions = actions;
        this.catalogs = catalogs;
    }

    @Override
    public Panel read(UUID actor, UUID company, Filters filters, Detail detail, int page, int size) {
        return work.execute(actor, company, CompanyPermission.READ_PANEL, () -> {
            var membership = access.require(company, CompanyPermission.READ_PANEL);
            var now = Instant.now();
            var zone = ZoneId.of(membership.zonaHoraria());
            long offset = (long) page * size;

            return new Panel(company, membership.nombre(), zone.getId(), now, filters, page, size, detail,
                    evaluationProjection(actor, company, filters,
                            detail == Detail.EVALUACIONES ? size : 0, detail == Detail.EVALUACIONES ? offset : 0,
                            false).datos(),
                    riskProjection(actor, company, filters,
                            detail == Detail.RIESGOS ? size : 0, detail == Detail.RIESGOS ? offset : 0,
                            zone, now, false).datos(),
                    actions.read(actor, company, filters.desde(), filters.hasta(),
                            detail == Detail.ACCIONES ? size : 0, detail == Detail.ACCIONES ? offset : 0,
                            filters.estadoAccion(), filters.vencida(), now.atZone(zone).toLocalDate()));
        });
    }

    @Override
    public Report generate(UUID actor, UUID company, Filters filters, Set<Detail> sections) {
        return work.execute(actor, company, CompanyPermission.READ_PANEL, () -> {
            var membership = access.require(company, CompanyPermission.READ_PANEL);
            var now = Instant.now();
            var zone = ZoneId.of(membership.zonaHoraria());
            boolean showEvaluations = sections.contains(Detail.EVALUACIONES);
            var evaluation = showEvaluations || filters.evaluacionId() != null
                    ? evaluationProjection(actor, company, filters,
                        showEvaluations && filters.evaluacionId() != null ? 1 : 0, 0, showEvaluations)
                    : null;
            var risk = sections.contains(Detail.RIESGOS)
                    ? riskProjection(actor, company, filters, 0, 0, zone, now, true) : null;
            var action = sections.contains(Detail.ACCIONES)
                    ? actions.read(actor, company, filters.desde(), filters.hasta(), 0, 0,
                            filters.estadoAccion(), filters.vencida(), now.atZone(zone).toLocalDate()) : null;
            var evaluationMethods = evaluation == null ? Set.<UUID>of() : Set.copyOf(evaluation.versionesMetodologia());
            var riskMethods = risk == null ? Set.<UUID>of() : Set.copyOf(risk.versionesMetodologia());
            var methodIds = new HashSet<>(evaluationMethods);
            methodIds.addAll(riskMethods);
            List<MethodReference> methods = methodIds.isEmpty() ? List.of()
                    : catalogs.methodologyReferences(company, methodIds);
            return new Report(company, membership.nombre(), zone.getId(), now, filters,
                    Arrays.stream(Detail.values()).filter(sections::contains).toList(),
                    showEvaluations ? new ReportEvaluations(evaluation.datos(), methods.stream()
                            .filter(method -> evaluationMethods.contains(method.versionId())).toList()) : null,
                    risk == null ? null : new ReportRisks(risk.datos(), methods.stream()
                            .filter(method -> riskMethods.contains(method.versionId())).toList()), action);
        });
    }

    private EvaluationPanelProjection.Projection evaluationProjection(UUID actor, UUID company, Filters filters,
            int limit, long offset, boolean includeMethods) {
        return evaluations.read(actor, company, filters.desde(), filters.hasta(), limit, offset,
                filters.evaluacionId(), filters.estadoEvaluacion(), includeMethods);
    }

    private RiskPanelProjection.Projection riskProjection(UUID actor, UUID company, Filters filters,
            int limit, long offset, ZoneId zone, Instant now, boolean includeMethods) {
        return risks.read(actor, company, filters.desde(), filters.hasta(), limit, offset,
                filters.baseRiesgo(), filters.estadoRiesgo(), filters.situacionRiesgo(), filters.nivelRiesgo(),
                filters.probabilidad(), filters.impacto(), zone, now, includeMethods);
    }
}
