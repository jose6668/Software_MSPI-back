package software.mspi.gestion.internal.web;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.mspi.identidad.api.SessionIdentity;
import software.mspi.riesgos.api.InherentRisks.Page;
import software.mspi.riesgos.api.ResidualRisks;
import software.mspi.riesgos.api.ResidualRisks.Draft;
import software.mspi.riesgos.api.ResidualRisks.Residual;

import static software.mspi.gestion.internal.web.ApiInherentRiskController.normalized;
import static software.mspi.gestion.internal.web.ApiInherentRiskController.scope;

@RestController
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
class ApiResidualRiskController {
    static final String RESIDUALS = ApiInherentRiskController.RISK + "/valoraciones-residuales";
    static final String RESIDUAL = RESIDUALS + "/{residualId}";
    static final String CONFIRM = RESIDUAL + "/confirmar";

    private final ResidualRisks risks;

    ApiResidualRiskController(ResidualRisks risks) {
        this.risks = risks;
    }

    @GetMapping(path = RESIDUALS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Page<Residual>> list(@PathVariable UUID empresaId, @PathVariable UUID riesgoId,
            @RequestParam(defaultValue = "0") @Min(0) int pagina, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(risks.residuals(scope(empresaId, actor, request), riesgoId, pagina, tamano));
    }

    @PostMapping(path = RESIDUALS, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Residual> start(@PathVariable UUID empresaId, @PathVariable UUID riesgoId, @Valid @RequestBody Create input,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(risks.startResidual(scope(empresaId, actor, request), riesgoId, input.revisionEsperada(),
                        input.datos().draft(), normalized(input.motivoReevaluacion())));
    }

    @PutMapping(path = RESIDUAL, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Residual> edit(@PathVariable UUID empresaId, @PathVariable UUID riesgoId, @PathVariable UUID residualId,
            @Valid @RequestBody Edit input, @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(risks.editResidual(scope(empresaId, actor, request), riesgoId, residualId, input.revisionEsperada(),
                        input.datos().draft()));
    }

    @PostMapping(path = CONFIRM, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Residual> confirm(@PathVariable UUID empresaId, @PathVariable UUID riesgoId, @PathVariable UUID residualId,
            @Valid @RequestBody ApiInherentRiskController.Revision input, @AuthenticationPrincipal SessionIdentity actor,
            HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(risks.confirmResidual(scope(empresaId, actor, request), riesgoId, residualId, input.revisionEsperada()));
    }

    record Create(@NotNull @PositiveOrZero Long revisionEsperada, @Valid @NotNull Data datos,
                  @Size(max = 2000) String motivoReevaluacion) {}
    record Edit(@NotNull @PositiveOrZero Long revisionEsperada, @Valid @NotNull Data datos) {}

    record Data(@Valid @NotNull ApiInherentRiskController.AssessmentInputs entradas, Boolean sinMitigacion,
                @Size(max = 2000) String justificacionSinMitigacion, @Size(max = 100) List<@NotNull UUID> verificaciones) {
        Draft draft() {
            return new Draft(entradas.inputs(), sinMitigacion, normalized(justificacionSinMitigacion),
                    verificaciones == null ? List.of() : List.copyOf(verificaciones));
        }
    }
}
