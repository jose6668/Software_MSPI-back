package software.mspi.gestion.internal.web;

import java.time.LocalDate;
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
import software.mspi.evaluaciones.api.EvaluationDrafts;
import software.mspi.evaluaciones.api.EvaluationDrafts.Answer;
import software.mspi.evaluaciones.api.EvaluationDrafts.Applicability;
import software.mspi.evaluaciones.api.EvaluationDrafts.Create;
import software.mspi.evaluaciones.api.EvaluationDrafts.ElementType;
import software.mspi.evaluaciones.api.EvaluationDrafts.Evaluation;
import software.mspi.evaluaciones.api.EvaluationDrafts.Finding;
import software.mspi.evaluaciones.api.EvaluationDrafts.Gap;
import software.mspi.evaluaciones.api.EvaluationDrafts.Page;
import software.mspi.evaluaciones.api.EvaluationDrafts.Response;
import software.mspi.evaluaciones.api.EvaluationDrafts.Results;
import software.mspi.evaluaciones.api.EvaluationDrafts.Scope;
import software.mspi.identidad.api.SessionIdentity;

@RestController
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
class ApiEvaluationController {

    static final String EVALUATIONS = "/api/v1/empresas/{empresaId}/evaluaciones";
    static final String EVALUATION = EVALUATIONS + "/{evaluacionId}";
    static final String RESPONSES = EVALUATION + "/respuestas";
    static final String RESPONSE = RESPONSES + "/{respuestaId}";
    static final String RESULTS = EVALUATION + "/resultados";
    static final String FINDINGS = RESPONSE + "/hallazgos";
    static final String FINDING = FINDINGS + "/{hallazgoId}";

    private final EvaluationDrafts evaluations;

    ApiEvaluationController(EvaluationDrafts evaluations) {
        this.evaluations = evaluations;
    }

    @GetMapping(path = EVALUATIONS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Page<Evaluation>> list(@PathVariable UUID empresaId,
            @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(evaluations.list(scope(empresaId, actor, request), pagina, tamano));
    }

    @GetMapping(path = EVALUATION, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Evaluation> get(@PathVariable UUID empresaId, @PathVariable UUID evaluacionId,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(evaluations.get(scope(empresaId, actor, request), evaluacionId));
    }

    @PostMapping(path = EVALUATIONS, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Evaluation> create(@PathVariable UUID empresaId, @Valid @RequestBody NewEvaluation input,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        var evaluation = new Create(input.nombre().strip(), input.fechaEvaluacion(), input.alcance().strip(),
                input.responsableId(), input.versionCatalogoId(), input.versionMetodologiaId());
        return created(evaluations.create(scope(empresaId, actor, request), evaluation));
    }

    @GetMapping(path = RESPONSES, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Page<Response>> responses(@PathVariable UUID empresaId, @PathVariable UUID evaluacionId,
            @RequestParam(required = false) ElementType tipo, @RequestParam(required = false) @Size(max = 80) String grupo,
            @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(evaluations.responses(scope(empresaId, actor, request), evaluacionId, tipo, normalized(grupo), pagina, tamano));
    }

    @PutMapping(path = RESPONSE, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Response> answer(@PathVariable UUID empresaId, @PathVariable UUID evaluacionId,
            @PathVariable UUID respuestaId, @Valid @RequestBody EditAnswer input,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        var answer = new Answer(input.aplicabilidad(), normalized(input.justificacionAplicabilidad()),
                input.calificacion(), normalized(input.fundamento()));
        return view(evaluations.updateResponse(scope(empresaId, actor, request), evaluacionId, respuestaId,
                input.revisionEsperada(), answer));
    }

    @GetMapping(path = RESULTS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Results> results(@PathVariable UUID empresaId, @PathVariable UUID evaluacionId,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(evaluations.results(scope(empresaId, actor, request), evaluacionId));
    }

    @GetMapping(path = FINDINGS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Page<Finding>> findings(@PathVariable UUID empresaId, @PathVariable UUID evaluacionId,
            @PathVariable UUID respuestaId, @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(evaluations.findings(scope(empresaId, actor, request), evaluacionId, respuestaId, pagina, tamano));
    }

    @PostMapping(path = FINDINGS, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Finding> createFinding(@PathVariable UUID empresaId, @PathVariable UUID evaluacionId,
            @PathVariable UUID respuestaId, @Valid @RequestBody EditFinding input,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return created(evaluations.createFinding(scope(empresaId, actor, request), evaluacionId, respuestaId,
                input.revisionEsperada(), input.gap()));
    }

    @PutMapping(path = FINDING, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Finding> updateFinding(@PathVariable UUID empresaId, @PathVariable UUID evaluacionId,
            @PathVariable UUID respuestaId, @PathVariable UUID hallazgoId, @Valid @RequestBody EditFinding input,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(evaluations.updateFinding(scope(empresaId, actor, request), evaluacionId, respuestaId, hallazgoId,
                input.revisionEsperada(), input.gap()));
    }

    private static Scope scope(UUID company, SessionIdentity actor, HttpServletRequest request) {
        if (actor == null) {
            throw new AccessDeniedException("Acceso empresarial no autorizado.");
        }
        return new Scope(actor.usuarioId(), company, ApiCorrelationFilter.id(request));
    }

    private static String normalized(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static <T> ResponseEntity<T> view(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static <T> ResponseEntity<T> created(T body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body);
    }

    record NewEvaluation(@NotBlank @Size(max = 200) String nombre, @NotNull LocalDate fechaEvaluacion,
                         @NotBlank String alcance, @NotNull UUID responsableId,
                         @NotNull UUID versionCatalogoId, @NotNull UUID versionMetodologiaId) {}

    record EditAnswer(@NotNull @PositiveOrZero Long revisionEsperada, @NotNull Applicability aplicabilidad,
                      @Size(max = 2000) String justificacionAplicabilidad, Integer calificacion, String fundamento) {}

    record EditFinding(@NotNull @PositiveOrZero Long revisionEsperada, @NotBlank String descripcionBrecha,
                       String recomendacion) {
        Gap gap() {
            return new Gap(descripcionBrecha.strip(), normalized(recomendacion));
        }
    }
}
