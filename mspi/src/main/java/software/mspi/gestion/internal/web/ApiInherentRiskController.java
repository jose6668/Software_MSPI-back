package software.mspi.gestion.internal.web;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.mspi.identidad.api.SessionIdentity;
import software.mspi.riesgos.api.InherentRisks;
import software.mspi.riesgos.api.InherentRisks.*;

@RestController
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
class ApiInherentRiskController {
    static final String RISKS = "/api/v1/empresas/{empresaId}/riesgos";
    static final String RISK = RISKS + "/{riesgoId}";
    static final String ANALYZE = RISK + "/iniciar-analisis";
    static final String ASSESSMENTS = RISK + "/valoraciones-inherentes";
    static final String ASSESSMENT = ASSESSMENTS + "/{valoracionId}";
    static final String CONFIRM = ASSESSMENT + "/confirmar";
    static final String LINKS = RISK + "/implementaciones-control";

    private final InherentRisks risks;

    ApiInherentRiskController(InherentRisks risks) {
        this.risks = risks;
    }

    @GetMapping(path = RISKS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Page<Risk>> list(@PathVariable UUID empresaId, @RequestParam(required = false) @Size(max = 80) String codigo,
            @RequestParam(required = false) State estado, @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano, @AuthenticationPrincipal SessionIdentity actor,
            HttpServletRequest request) {
        return ok(risks.list(scope(empresaId, actor, request), normalized(codigo), estado, pagina, tamano));
    }

    @GetMapping(path = RISK, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Risk> get(@PathVariable UUID empresaId, @PathVariable UUID riesgoId,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return ok(risks.get(scope(empresaId, actor, request), riesgoId));
    }

    @PostMapping(path = RISKS, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Risk> create(@PathVariable UUID empresaId, @Valid @RequestBody Create input,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return created(risks.create(scope(empresaId, actor, request), input.codigo().strip(), input.detalle().details()));
    }

    @PutMapping(path = RISK, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Risk> edit(@PathVariable UUID empresaId, @PathVariable UUID riesgoId, @Valid @RequestBody Edit input,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return ok(risks.edit(scope(empresaId, actor, request), riesgoId, input.revisionEsperada(),
                input.detalle().details(), normalized(input.motivo())));
    }

    @PostMapping(path = ANALYZE, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Risk> analyze(@PathVariable UUID empresaId, @PathVariable UUID riesgoId, @Valid @RequestBody Revision input,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return ok(risks.startAnalysis(scope(empresaId, actor, request), riesgoId, input.revisionEsperada()));
    }

    @GetMapping(path = ASSESSMENTS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Page<Assessment>> assessments(@PathVariable UUID empresaId, @PathVariable UUID riesgoId,
            @RequestParam(defaultValue = "0") @Min(0) int pagina, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return ok(risks.assessments(scope(empresaId, actor, request), riesgoId, pagina, tamano));
    }

    @PostMapping(path = ASSESSMENTS, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Assessment> startAssessment(@PathVariable UUID empresaId, @PathVariable UUID riesgoId,
            @Valid @RequestBody NewAssessment input, @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return created(risks.startAssessment(scope(empresaId, actor, request), riesgoId, input.revisionEsperada(),
                input.entradas().inputs(), normalized(input.motivoReevaluacion())));
    }

    @PutMapping(path = ASSESSMENT, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Assessment> editAssessment(@PathVariable UUID empresaId, @PathVariable UUID riesgoId,
            @PathVariable UUID valoracionId, @Valid @RequestBody EditAssessment input,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return ok(risks.editAssessment(scope(empresaId, actor, request), riesgoId, valoracionId,
                input.revisionEsperada(), input.entradas().inputs()));
    }

    @PostMapping(path = CONFIRM, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Assessment> confirm(@PathVariable UUID empresaId, @PathVariable UUID riesgoId, @PathVariable UUID valoracionId,
            @Valid @RequestBody Revision input, @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return ok(risks.confirmAssessment(scope(empresaId, actor, request), riesgoId, valoracionId, input.revisionEsperada()));
    }

    @GetMapping(path = LINKS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Page<Link>> links(@PathVariable UUID empresaId, @PathVariable UUID riesgoId,
            @RequestParam(defaultValue = "0") @Min(0) int pagina, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return ok(risks.links(scope(empresaId, actor, request), riesgoId, pagina, tamano));
    }

    @PostMapping(path = LINKS, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Link> link(@PathVariable UUID empresaId, @PathVariable UUID riesgoId, @Valid @RequestBody NewLink input,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return created(risks.link(scope(empresaId, actor, request), riesgoId, input.revisionEsperada(), input.implementacionId(),
                input.efectoEsperado(), input.justificacion().strip()));
    }

    static Scope scope(UUID company, SessionIdentity actor, HttpServletRequest request) {
        if (actor == null) throw new AccessDeniedException("Acceso empresarial no autorizado.");
        return new Scope(actor.usuarioId(), company, ApiCorrelationFilter.id(request));
    }

    static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static <T> ResponseEntity<T> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    private static <T> ResponseEntity<T> created(T value) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(value);
    }

    record RiskDetails(@NotNull UUID activoPrincipalId, @NotBlank @Size(max = 1000) String amenaza,
                       @NotBlank @Size(max = 1000) String vulnerabilidad, @NotBlank String descripcion,
                       @NotNull UUID responsableId, Treatment tratamientoPropuesto) {
        Details details() {
            return new Details(activoPrincipalId, amenaza.strip(), vulnerabilidad.strip(), descripcion.strip(),
                    responsableId, tratamientoPropuesto);
        }
    }

    record AssessmentInputs(Probability probabilidad, Impact impacto, @Size(max = 150) String periodoReferencia,
                            String fundamento, String supuestos, String fuente) {
        Inputs inputs() {
            return new Inputs(probabilidad, impacto, normalized(periodoReferencia), normalized(fundamento),
                    normalized(supuestos), normalized(fuente));
        }
    }

    record Create(@NotBlank @Size(max = 80) String codigo, @NotNull @Valid RiskDetails detalle) {}
    record Edit(@NotNull @PositiveOrZero Long revisionEsperada, @NotNull @Valid RiskDetails detalle,
                @Size(max = 2000) String motivo) {}
    record Revision(@NotNull @PositiveOrZero Long revisionEsperada) {}
    record NewAssessment(@NotNull @PositiveOrZero Long revisionEsperada, @NotNull @Valid AssessmentInputs entradas,
                         @Size(max = 2000) String motivoReevaluacion) {}
    record EditAssessment(@NotNull @PositiveOrZero Long revisionEsperada, @NotNull @Valid AssessmentInputs entradas) {}
    record NewLink(@NotNull @PositiveOrZero Long revisionEsperada, @NotNull UUID implementacionId,
                   @NotNull Effect efectoEsperado, @NotBlank String justificacion) {}
}
