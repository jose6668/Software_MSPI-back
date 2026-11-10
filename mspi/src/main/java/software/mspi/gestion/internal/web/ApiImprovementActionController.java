package software.mspi.gestion.internal.web;

import java.time.LocalDate;
import java.util.List;
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
import software.mspi.acciones.api.ImprovementActions;
import software.mspi.acciones.api.ImprovementActions.Action;
import software.mspi.acciones.api.ImprovementActions.ContextEvidence;
import software.mspi.acciones.api.ImprovementActions.Draft;
import software.mspi.acciones.api.ImprovementActions.Page;
import software.mspi.identidad.api.SessionIdentity;

@RestController
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
class ApiImprovementActionController {
    static final String ACTIONS = "/api/v1/empresas/{empresaId}/acciones";
    static final String ACTION = ACTIONS + "/{accionId}";
    static final String CANCEL = ACTION + "/cancelar";

    private final ImprovementActions actions;

    ApiImprovementActionController(ImprovementActions actions) {
        this.actions = actions;
    }

    @GetMapping(path = ACTIONS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Page> list(@PathVariable UUID empresaId, @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return ok(actions.actions(scope(empresaId, actor, request), pagina, tamano));
    }

    @GetMapping(path = ACTION, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Action> get(@PathVariable UUID empresaId, @PathVariable UUID accionId,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return ok(actions.get(scope(empresaId, actor, request), accionId));
    }

    @PostMapping(path = ACTIONS, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Action> create(@PathVariable UUID empresaId, @Valid @RequestBody Create input,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(actions.create(scope(empresaId, actor, request), input.datos().draft()));
    }

    @PutMapping(path = ACTION, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Action> edit(@PathVariable UUID empresaId, @PathVariable UUID accionId, @Valid @RequestBody Edit input,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return ok(actions.edit(scope(empresaId, actor, request), accionId, input.revisionEsperada(),
                input.datos().draft(), input.motivo().strip()));
    }

    @PostMapping(path = CANCEL, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Action> cancel(@PathVariable UUID empresaId, @PathVariable UUID accionId, @Valid @RequestBody Cancel input,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return ok(actions.cancel(scope(empresaId, actor, request), accionId, input.revisionEsperada(), input.motivo().strip()));
    }

    private static ImprovementActions.Scope scope(UUID company, SessionIdentity actor, HttpServletRequest request) {
        if (actor == null) throw new AccessDeniedException("Acceso empresarial no autorizado.");
        return new ImprovementActions.Scope(actor.usuarioId(), company, ApiCorrelationFilter.id(request));
    }

    private static <T> ResponseEntity<T> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    record Create(@NotNull @Valid Data datos) {}
    record Edit(@NotNull @PositiveOrZero Long revisionEsperada, @NotNull @Valid Data datos,
                @NotBlank @Size(max = 2000) String motivo) {}
    record Cancel(@NotNull @PositiveOrZero Long revisionEsperada, @NotBlank @Size(max = 2000) String motivo) {}
    record Data(UUID riesgoId, UUID hallazgoId, @NotBlank String descripcion, @NotBlank String resultadoEsperado,
                @NotNull UUID responsableMembresiaId, @NotNull LocalDate fechaLimite,
                @NotNull Boolean evidenciaRequerida, @NotBlank @Size(max = 2000) String descripcionEvidencia,
                @Size(max = 2000) String justificacionSinEvidencia, @NotBlank String resumenContextoRa,
                @Size(max = 20) List<@Valid Context> evidenciasContexto) {
        Draft draft() {
            return new Draft(riesgoId, hallazgoId, descripcion, resultadoEsperado, responsableMembresiaId, fechaLimite,
                    evidenciaRequerida, descripcionEvidencia, ApiInherentRiskController.normalized(justificacionSinEvidencia),
                    resumenContextoRa, evidenciasContexto == null ? List.of()
                            : evidenciasContexto.stream().map(Context::evidence).toList());
        }
    }
    record Context(@NotNull UUID evidenciaId, @NotBlank @Size(max = 1000) String motivoContexto) {
        ContextEvidence evidence() { return new ContextEvidence(evidenciaId, motivoContexto); }
    }
}
