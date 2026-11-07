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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.mspi.activos.api.AssetClassification;
import software.mspi.activos.api.AssetClassification.Authorization;
import software.mspi.activos.api.AssetClassification.Input;
import software.mspi.activos.api.AssetClassification.PersonalDataType;
import software.mspi.activos.api.AssetClassification.Valuation;
import software.mspi.activos.api.ProcessCatalog.Page;
import software.mspi.activos.api.ProcessCatalog.Scope;
import software.mspi.identidad.api.SessionIdentity;

@RestController
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
class ApiAssetClassificationController {

    static final String CLASSIFICATIONS = ApiAssetInventoryController.ASSET + "/clasificaciones";
    static final String CLASSIFICATION = CLASSIFICATIONS + "/{valoracionId}";
    static final String CONFIRM = CLASSIFICATION + "/confirmar";

    private final AssetClassification classifications;

    ApiAssetClassificationController(AssetClassification classifications) {
        this.classifications = classifications;
    }

    @GetMapping(path = CLASSIFICATIONS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Page<Valuation>> list(@PathVariable UUID empresaId, @PathVariable UUID activoId,
            @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(classifications.list(scope(empresaId, actor, request), activoId, pagina, tamano));
    }

    @GetMapping(path = CLASSIFICATION, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Valuation> get(@PathVariable UUID empresaId, @PathVariable UUID activoId,
            @PathVariable UUID valoracionId, @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(classifications.get(scope(empresaId, actor, request), activoId, valoracionId));
    }

    @PostMapping(path = CLASSIFICATIONS, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Valuation> start(@PathVariable UUID empresaId, @PathVariable UUID activoId,
            @Valid @RequestBody Start input, @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        String reason = input.motivoRevision() == null || input.motivoRevision().isBlank()
                ? null : input.motivoRevision().strip();
        var created = classifications.start(scope(empresaId, actor, request), activoId, input.entradas().toInput(), reason);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(created);
    }

    @PutMapping(path = CLASSIFICATION, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Valuation> edit(@PathVariable UUID empresaId, @PathVariable UUID activoId,
            @PathVariable UUID valoracionId, @Valid @RequestBody Edit input,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(classifications.edit(scope(empresaId, actor, request), activoId, valoracionId,
                input.revisionEsperada(), input.entradas().toInput()));
    }

    @PostMapping(path = CONFIRM, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Valuation> confirm(@PathVariable UUID empresaId, @PathVariable UUID activoId,
            @PathVariable UUID valoracionId, @Valid @RequestBody Confirm input,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(classifications.confirm(scope(empresaId, actor, request), activoId, valoracionId,
                input.revisionEsperada()));
    }

    private static Scope scope(UUID company, SessionIdentity actor, HttpServletRequest request) {
        if (actor == null) {
            throw new AccessDeniedException("Acceso empresarial no autorizado.");
        }
        return new Scope(actor.usuarioId(), company, ApiCorrelationFilter.id(request));
    }

    private static <T> ResponseEntity<T> view(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    record Start(@NotNull @Valid Entries entradas, @Size(max = 1000) String motivoRevision) {}
    record Edit(@NotNull @PositiveOrZero Long revisionEsperada, @NotNull @Valid Entries entradas) {}
    record Confirm(@NotNull @PositiveOrZero Long revisionEsperada) {}

    record Entries(Integer confidencialidad, Integer integridad, Integer disponibilidad, String fundamento,
                   Boolean contieneDatosPersonales, Boolean contieneDatosMenores,
                   @Size(max = 6) List<@NotNull PersonalDataType> tiposDatosPersonales,
                   String finalidadTratamiento, Authorization autorizacionTratamiento,
                   @Size(max = 2000) String justificacionNoAplica) {
        Input toInput() {
            return new Input(confidencialidad, integridad, disponibilidad, fundamento, contieneDatosPersonales,
                    contieneDatosMenores, tiposDatosPersonales, finalidadTratamiento, autorizacionTratamiento,
                    justificacionNoAplica);
        }
    }
}
