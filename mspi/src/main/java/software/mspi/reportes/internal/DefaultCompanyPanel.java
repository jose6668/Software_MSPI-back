package software.mspi.reportes.internal;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.mspi.acciones.api.ActionPanelProjection;
import software.mspi.empresas.api.CompanyAccess;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;
import software.mspi.evaluaciones.api.EvaluationPanelProjection;
import software.mspi.reportes.api.CompanyPanel;
import software.mspi.riesgos.api.RiskPanelProjection;

@Service
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
final class DefaultCompanyPanel implements CompanyPanel {
    private final TenantUnitOfWork work;
    private final CompanyAccess access;
    private final EvaluationPanelProjection evaluations;
    private final RiskPanelProjection risks;
    private final ActionPanelProjection actions;

    DefaultCompanyPanel(TenantUnitOfWork work, CompanyAccess access, EvaluationPanelProjection evaluations,
                        RiskPanelProjection risks, ActionPanelProjection actions) {
        this.work = work;
        this.access = access;
        this.evaluations = evaluations;
        this.risks = risks;
        this.actions = actions;
    }

    @Override
    public Panel read(UUID actor, UUID company, Filters filters, Detail detail, int page, int size) {
        return work.execute(actor, company, CompanyPermission.READ_PANEL, () -> {
            var membership = access.require(company, CompanyPermission.READ_PANEL);
            var now = Instant.now();
            var zone = ZoneId.of(membership.zonaHoraria());
            long offset = (long) page * size;

            return new Panel(company, membership.nombre(), zone.getId(), now, filters, page, size, detail,
                    evaluations.read(actor, company, filters.desde(), filters.hasta(),
                            detail == Detail.EVALUACIONES ? size : 0, detail == Detail.EVALUACIONES ? offset : 0,
                            filters.evaluacionId(), filters.estadoEvaluacion()),
                    risks.read(actor, company, filters.desde(), filters.hasta(),
                            detail == Detail.RIESGOS ? size : 0, detail == Detail.RIESGOS ? offset : 0,
                            filters.baseRiesgo(), filters.estadoRiesgo(), filters.situacionRiesgo(),
                            filters.nivelRiesgo(), filters.probabilidad(), filters.impacto(), zone, now),
                    actions.read(actor, company, filters.desde(), filters.hasta(),
                            detail == Detail.ACCIONES ? size : 0, detail == Detail.ACCIONES ? offset : 0,
                            filters.estadoAccion(), filters.vencida(), now.atZone(zone).toLocalDate()));
        });
    }
}
