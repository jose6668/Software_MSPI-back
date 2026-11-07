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
import software.mspi.activos.api.AssetInventory;
import software.mspi.activos.api.AssetInventory.Asset;
import software.mspi.activos.api.AssetInventory.Filter;
import software.mspi.activos.api.AssetInventory.Input;
import software.mspi.activos.api.AssetInventory.References;
import software.mspi.activos.api.AssetInventory.Type;
import software.mspi.activos.api.ProcessCatalog.Page;
import software.mspi.activos.api.ProcessCatalog.Scope;
import software.mspi.activos.api.ProcessCatalog.State;
import software.mspi.auditoria.api.ActivityLog.Entry;
import software.mspi.identidad.api.SessionIdentity;

@RestController
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
class ApiAssetInventoryController {

    static final String ASSETS = "/api/v1/empresas/{empresaId}/activos";
    static final String ASSET = ASSETS + "/{activoId}";
    static final String ARCHIVE = ASSET + "/archivar";
    static final String RESTORE = ASSET + "/restaurar";
    static final String HISTORY = ASSET + "/historial";

    private final AssetInventory inventory;

    ApiAssetInventoryController(AssetInventory inventory) {
        this.inventory = inventory;
    }

    @GetMapping(path = ASSETS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Page<Asset>> list(@PathVariable UUID empresaId,
            @RequestParam(required = false) @Size(max = 250) String texto,
            @RequestParam(required = false) State estado, @RequestParam(required = false) Type tipo,
            @RequestParam(required = false) UUID procesoId, @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        var filter = new Filter(texto, estado, tipo, procesoId);
        return view(inventory.list(scope(empresaId, actor, request), filter, pagina, tamano));
    }

    @GetMapping(path = ASSET, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Asset> get(@PathVariable UUID empresaId, @PathVariable UUID activoId,
                              @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(inventory.get(scope(empresaId, actor, request), activoId));
    }

    @GetMapping(path = HISTORY, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Page<Entry>> history(@PathVariable UUID empresaId, @PathVariable UUID activoId,
            @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(inventory.history(scope(empresaId, actor, request), activoId, pagina, tamano));
    }

    @PostMapping(path = ASSETS, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Asset> create(@PathVariable UUID empresaId, @Valid @RequestBody Create input,
                                 @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        var asset = inventory.create(scope(empresaId, actor, request), input.codigo(),
                new Input(input.nombre().strip(), input.descripcion(), input.tipo(),
                        new References(input.procesoId(), input.propietarioId(), input.custodioId()), input.ubicacion()));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(asset);
    }

    @PutMapping(path = ASSET, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Asset> edit(@PathVariable UUID empresaId, @PathVariable UUID activoId, @Valid @RequestBody Edit input,
                               @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(inventory.edit(scope(empresaId, actor, request), activoId, input.revisionEsperada(),
                new Input(input.nombre().strip(), input.descripcion(), input.tipo(),
                        new References(input.procesoId(), input.propietarioId(), input.custodioId()), input.ubicacion())));
    }

    @PostMapping(path = ARCHIVE, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Asset> archive(@PathVariable UUID empresaId, @PathVariable UUID activoId,
            @Valid @RequestBody Archive input, @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(inventory.archive(scope(empresaId, actor, request), activoId,
                input.revisionEsperada(), input.motivo().strip()));
    }

    @PostMapping(path = RESTORE, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Asset> restore(@PathVariable UUID empresaId, @PathVariable UUID activoId,
            @Valid @RequestBody Restore input, @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        var refs = input.referencias();
        return view(inventory.restore(scope(empresaId, actor, request), activoId, input.revisionEsperada(),
                input.motivo().strip(), refs == null ? null : new References(refs.procesoId(), refs.propietarioId(), refs.custodioId())));
    }

    private static Scope scope(UUID empresa, SessionIdentity actor, HttpServletRequest request) {
        if (actor == null) {
            throw new AccessDeniedException("Acceso empresarial no autorizado.");
        }
        return new Scope(actor.usuarioId(), empresa, ApiCorrelationFilter.id(request));
    }

    private static <T> ResponseEntity<T> view(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    record Create(@NotBlank @Size(max = 80) String codigo, @NotBlank @Size(max = 250) String nombre,
                  @NotBlank String descripcion, @NotNull Type tipo, @NotNull UUID procesoId, @NotNull UUID propietarioId,
                  UUID custodioId, @Size(max = 1000) String ubicacion) {}

    record Edit(@NotNull @PositiveOrZero Long revisionEsperada, @NotBlank @Size(max = 250) String nombre,
                @NotBlank String descripcion, @NotNull Type tipo, @NotNull UUID procesoId, @NotNull UUID propietarioId,
                UUID custodioId, @Size(max = 1000) String ubicacion) {}

    record Archive(@NotNull @PositiveOrZero Long revisionEsperada, @NotBlank @Size(max = 1000) String motivo) {}

    record Restore(@NotNull @PositiveOrZero Long revisionEsperada, @NotBlank @Size(max = 1000) String motivo,
                   @Valid RestoreReferences referencias) {}

    record RestoreReferences(@NotNull UUID procesoId, @NotNull UUID propietarioId, UUID custodioId) {}
}
