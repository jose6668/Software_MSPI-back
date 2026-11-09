package software.mspi.gestion.internal.web;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.mspi.controles.api.ControlImplementations;
import software.mspi.controles.api.ControlImplementations.Details;
import software.mspi.controles.api.ControlImplementations.Implementation;
import software.mspi.controles.api.ControlImplementations.Mode;
import software.mspi.controles.api.ControlImplementations.Page;
import software.mspi.controles.api.ControlImplementations.Scope;
import software.mspi.controles.api.ControlImplementations.Test;
import software.mspi.controles.api.ControlImplementations.Type;
import software.mspi.controles.api.ControlImplementations.Verification;
import software.mspi.controles.api.ControlImplementations.Version;
import software.mspi.evidencias.api.ControlEvidence;
import software.mspi.identidad.api.SessionIdentity;

@RestController
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
class ApiControlImplementationController {
    static final String CONTROLS = "/api/v1/empresas/{empresaId}/implementaciones-control";
    static final String CONTROL = CONTROLS + "/{implementacionId}";
    static final String VERSIONS = CONTROL + "/versiones";
    static final String VERSION = VERSIONS + "/{versionId}";
    static final String VERIFICATIONS = VERSION + "/verificaciones";
    static final String START = CONTROL + "/iniciar";
    static final String APPLY = CONTROL + "/confirmar-aplicacion";
    static final String CHANGE = CONTROL + "/cambiar";
    static final String VERIFY = CONTROL + "/verificar";

    private final ControlImplementations controls;
    private final ObjectProvider<ControlEvidence> evidence;

    ApiControlImplementationController(ControlImplementations controls, ObjectProvider<ControlEvidence> evidence) {
        this.controls = controls;
        this.evidence = evidence;
    }

    @GetMapping(path = CONTROLS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Page<Implementation>> list(@PathVariable UUID empresaId,
            @RequestParam(defaultValue = "0") @Min(0) int pagina, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(controls.list(scope(empresaId, actor, request), pagina, tamano));
    }

    @GetMapping(path = CONTROL, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Implementation> get(@PathVariable UUID empresaId, @PathVariable UUID implementacionId,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(controls.get(scope(empresaId, actor, request), implementacionId));
    }

    @GetMapping(path = VERSIONS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Page<Version>> versions(@PathVariable UUID empresaId, @PathVariable UUID implementacionId,
            @RequestParam(defaultValue = "0") @Min(0) int pagina, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(controls.versions(scope(empresaId, actor, request), implementacionId, pagina, tamano));
    }

    @GetMapping(path = VERIFICATIONS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Page<Verification>> verifications(@PathVariable UUID empresaId, @PathVariable UUID implementacionId,
            @PathVariable UUID versionId, @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(controls.verifications(scope(empresaId, actor, request), implementacionId, versionId, pagina, tamano));
    }

    @PostMapping(path = CONTROLS, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Implementation> create(@PathVariable UUID empresaId, @Valid @RequestBody Create input,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return created(controls.create(scope(empresaId, actor, request), input.versionCatalogoId(), input.elementoId(),
                input.detalle().details()));
    }

    @PostMapping(path = START, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Implementation> start(@PathVariable UUID empresaId, @PathVariable UUID implementacionId,
            @Valid @RequestBody Revision input, @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(controls.start(scope(empresaId, actor, request), implementacionId, input.revisionEsperada()));
    }

    @PostMapping(path = APPLY, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Implementation> apply(@PathVariable UUID empresaId, @PathVariable UUID implementacionId,
            @Valid @RequestBody Apply input, @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(controls.apply(scope(empresaId, actor, request), implementacionId, input.revisionEsperada(),
                input.fechaAplicacion(), input.evidenciaId(), this::verifiedHash));
    }

    @PostMapping(path = CHANGE, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Implementation> change(@PathVariable UUID empresaId, @PathVariable UUID implementacionId,
            @Valid @RequestBody Change input, @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(controls.change(scope(empresaId, actor, request), implementacionId, input.revisionEsperada(),
                input.detalle().details(), input.motivo().strip()));
    }

    @PostMapping(path = VERIFY, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Verification> verify(@PathVariable UUID empresaId, @PathVariable UUID implementacionId,
            @Valid @RequestBody Verify input, @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return created(controls.verify(scope(empresaId, actor, request), implementacionId, input.revisionEsperada(),
                input.test(), this::verifiedHash));
    }

    private Optional<String> verifiedHash(Scope scope, UUID implementation, UUID version, UUID id) {
        return Optional.ofNullable(evidence.getIfAvailable())
                .flatMap(store -> store.verifiedHash(scope, implementation, version, id));
    }

    static Scope scope(UUID company, SessionIdentity actor, HttpServletRequest request) {
        if (actor == null) throw new AccessDeniedException("Acceso empresarial no autorizado.");
        return new Scope(actor.usuarioId(), company, ApiCorrelationFilter.id(request));
    }

    private static <T> ResponseEntity<T> view(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static <T> ResponseEntity<T> created(T body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body);
    }

    record DetailsInput(@NotBlank @Size(max = 250) String nombre, @NotBlank String descripcion, @NotBlank String alcance,
                        @NotBlank String trabajo, @NotNull UUID responsableId, Type tipo, Mode modo) {
        Details details() {
            return new Details(nombre.strip(), descripcion.strip(), alcance.strip(), trabajo.strip(), responsableId, tipo, modo);
        }
    }

    record Create(@NotNull UUID versionCatalogoId, @NotNull UUID elementoId, @NotNull @Valid DetailsInput detalle) {}
    record Revision(@NotNull @PositiveOrZero Long revisionEsperada) {}
    record Apply(@NotNull @PositiveOrZero Long revisionEsperada, @NotNull LocalDate fechaAplicacion, @NotNull UUID evidenciaId) {}
    record Change(@NotNull @PositiveOrZero Long revisionEsperada, @NotNull @Valid DetailsInput detalle,
                  @NotBlank @Size(max = 2000) String motivo) {}
    record Verify(@NotNull @PositiveOrZero Long revisionEsperada, @NotBlank String prueba, @NotBlank String fundamento,
                  @NotNull @Positive Integer criteriosEvaluados, @NotNull @PositiveOrZero Integer criteriosCumplidos,
                  @NotNull LocalDate proximaRevision, @NotNull UUID evidenciaId) {
        Test test() {
            return new Test(prueba.strip(), fundamento.strip(), criteriosEvaluados, criteriosCumplidos, proximaRevision, evidenciaId);
        }
    }
}
