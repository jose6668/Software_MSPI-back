package software.mspi.gestion.internal.web;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.mspi.acciones.api.ActionPanelProjection.ActionState;
import software.mspi.evaluaciones.api.EvaluationPanelProjection.EvaluationState;
import software.mspi.identidad.api.SessionIdentity;
import software.mspi.reportes.api.CompanyPanel;
import software.mspi.reportes.api.CompanyPanel.Detail;
import software.mspi.reportes.api.CompanyPanel.Filters;
import software.mspi.reportes.api.CompanyPanel.Panel;
import software.mspi.reportes.api.CompanyReport;
import software.mspi.reportes.api.CompanyReport.Report;
import software.mspi.riesgos.api.InherentRisks.Impact;
import software.mspi.riesgos.api.InherentRisks.Level;
import software.mspi.riesgos.api.InherentRisks.Probability;
import software.mspi.riesgos.api.InherentRisks.State;
import software.mspi.riesgos.api.RiskPanelProjection.AssessmentStatus;
import software.mspi.riesgos.api.RiskPanelProjection.Basis;

@RestController
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
class ApiCompanyPanelController {
    static final String PANEL = "/api/v1/empresas/{empresaId}/panel";
    static final String REPORT = "/api/v1/empresas/{empresaId}/reportes/basico";

    private final CompanyPanel panel;
    private final CompanyReport report;

    ApiCompanyPanelController(CompanyPanel panel, CompanyReport report) {
        this.panel = panel;
        this.report = report;
    }

    @GetMapping(path = PANEL, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Panel> panel(@PathVariable UUID empresaId, @AuthenticationPrincipal SessionIdentity actor,
                               @Valid @ModelAttribute Query query, @RequestParam(required = false) Detail detalle,
                               @RequestParam(defaultValue = "0") @Min(0) int pagina,
                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano) {
        if (actor == null) throw new AccessDeniedException("Acceso empresarial no autorizado.");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(panel.read(actor.usuarioId(), empresaId, query.filters(), detalle, pagina, tamano));
    }

    @GetMapping(path = REPORT, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Report> report(@PathVariable UUID empresaId, @AuthenticationPrincipal SessionIdentity actor,
                                  @Valid @ModelAttribute Query query,
                                  @RequestParam(required = false) @Size(min = 1, max = 3)
                                  List<@NotNull Detail> secciones) {
        if (actor == null) throw new AccessDeniedException("Acceso empresarial no autorizado.");
        var sections = secciones == null ? EnumSet.allOf(Detail.class) : Set.copyOf(secciones);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(report.generate(actor.usuarioId(), empresaId, query.filters(), sections));
    }

    record Query(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
                 @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
                 UUID evaluacionId, EvaluationState estadoEvaluacion, State estadoRiesgo, Basis baseRiesgo,
                 AssessmentStatus situacionRiesgo, Level nivelRiesgo, Probability probabilidad, Impact impacto,
                 ActionState estadoAccion, Boolean vencida) {
        @AssertTrue(message = "La fecha inicial debe ser igual o anterior a la final.")
        public boolean isRangoValido() {
            return desde == null || hasta == null || !desde.isAfter(hasta);
        }

        Filters filters() {
            return new Filters(desde, hasta, evaluacionId, estadoEvaluacion, estadoRiesgo,
                    baseRiesgo == null ? Basis.RESIDUAL : baseRiesgo, situacionRiesgo,
                    nivelRiesgo, probabilidad, impacto, estadoAccion, vencida);
        }
    }
}
