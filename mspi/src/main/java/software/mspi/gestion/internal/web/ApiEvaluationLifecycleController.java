package software.mspi.gestion.internal.web;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import software.mspi.evaluaciones.api.EvaluationDrafts.Evaluation;
import software.mspi.evaluaciones.api.EvaluationDrafts.Response;
import software.mspi.evaluaciones.api.EvaluationDrafts.Scope;
import software.mspi.evaluaciones.api.EvaluationLifecycle;
import software.mspi.evaluaciones.api.EvaluationLifecycle.Closure;
import software.mspi.evidencias.api.ResponseEvidence;
import software.mspi.identidad.api.SessionIdentity;

@RestController
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
class ApiEvaluationLifecycleController {
    static final String SUBMIT = ApiEvaluationController.EVALUATION + "/enviar-revision";
    static final String RETURN = ApiEvaluationController.EVALUATION + "/devolver";
    static final String CLOSE = ApiEvaluationController.EVALUATION + "/cerrar";
    static final String CLOSURE = ApiEvaluationController.EVALUATION + "/cierre";
    static final String CORRECTIONS = ApiEvaluationController.EVALUATION + "/correcciones";
    static final String CONFIRM = ApiEvaluationController.RESPONSE + "/confirmar-heredada";

    private final EvaluationLifecycle evaluations;
    private final ObjectProvider<ResponseEvidence> evidence;

    ApiEvaluationLifecycleController(EvaluationLifecycle evaluations, ObjectProvider<ResponseEvidence> evidence) {
        this.evaluations = evaluations;
        this.evidence = evidence;
    }

    @PostMapping(path = SUBMIT, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Evaluation> submit(@PathVariable UUID empresaId, @PathVariable UUID evaluacionId,
            @Valid @RequestBody Review input, @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(evaluations.submit(ApiEvaluationController.scope(empresaId, actor, request), evaluacionId,
                input.revisionEsperada(), input.selected(), this::verifiedHash));
    }

    @PostMapping(path = RETURN, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Evaluation> returnToDraft(@PathVariable UUID empresaId, @PathVariable UUID evaluacionId,
            @Valid @RequestBody Return input, @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(evaluations.returnToDraft(ApiEvaluationController.scope(empresaId, actor, request), evaluacionId,
                input.revisionEsperada(), input.motivo().strip()));
    }

    @PostMapping(path = CLOSE, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Evaluation> close(@PathVariable UUID empresaId, @PathVariable UUID evaluacionId,
            @Valid @RequestBody Review input, @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(evaluations.close(ApiEvaluationController.scope(empresaId, actor, request), evaluacionId,
                input.revisionEsperada(), input.selected(), this::verifiedHash));
    }

    @GetMapping(path = CLOSURE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Closure> closure(@PathVariable UUID empresaId, @PathVariable UUID evaluacionId,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(evaluations.closure(ApiEvaluationController.scope(empresaId, actor, request), evaluacionId));
    }

    @PostMapping(path = CORRECTIONS, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Evaluation> correct(@PathVariable UUID empresaId, @PathVariable UUID evaluacionId,
            @Valid @RequestBody Correction input, @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        var result = evaluations.correct(ApiEvaluationController.scope(empresaId, actor, request), evaluacionId,
                input.revisionEsperada(), input.responsableId(), input.motivo().strip());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping(path = CONFIRM, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Response> confirmInherited(@PathVariable UUID empresaId, @PathVariable UUID evaluacionId,
            @PathVariable UUID respuestaId, @Valid @RequestBody Revision input, @AuthenticationPrincipal SessionIdentity actor,
            HttpServletRequest request) {
        return view(evaluations.confirmInherited(ApiEvaluationController.scope(empresaId, actor, request), evaluacionId,
                respuestaId, input.revisionEsperada()));
    }

    private Optional<String> verifiedHash(Scope scope, UUID evaluation, UUID response, UUID evidenceId) {
        var target = new ResponseEvidence.Target(scope.actor(), scope.company(), evaluation, response, scope.correlation());
        return Optional.ofNullable(evidence.getIfAvailable()).flatMap(store -> store.verifiedHash(target, evidenceId));
    }

    private static <T> ResponseEntity<T> view(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    record Review(@NotNull @PositiveOrZero Long revisionEsperada, @Size(max = 116) Map<UUID, @NotNull UUID> evidencias) {
        Map<UUID, UUID> selected() {
            return evidencias == null ? Map.of() : evidencias;
        }
    }

    record Return(@NotNull @PositiveOrZero Long revisionEsperada, @NotBlank @Size(max = 2000) String motivo) {}

    record Correction(@NotNull @PositiveOrZero Long revisionEsperada, @NotNull UUID responsableId,
                      @NotBlank @Size(max = 2000) String motivo) {}

    record Revision(@NotNull @PositiveOrZero Long revisionEsperada) {}
}
