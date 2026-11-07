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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.mspi.activos.api.ProcessCatalog;
import software.mspi.activos.api.ProcessCatalog.Page;
import software.mspi.activos.api.ProcessCatalog.Process;
import software.mspi.activos.api.ProcessCatalog.ProcessInput;
import software.mspi.activos.api.ProcessCatalog.Responsible;
import software.mspi.activos.api.ProcessCatalog.ResponsibleInput;
import software.mspi.activos.api.ProcessCatalog.ResponsibleType;
import software.mspi.activos.api.ProcessCatalog.Scope;
import software.mspi.activos.api.ProcessCatalog.State;
import software.mspi.identidad.api.SessionIdentity;

@RestController
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
class ApiProcessCatalogController {

    static final String PROCESSES = "/api/v1/empresas/{empresaId}/procesos";
    static final String PROCESS = PROCESSES + "/{procesoId}";
    static final String RESPONSIBLES = "/api/v1/empresas/{empresaId}/responsables-negocio";
    static final String RESPONSIBLE = RESPONSIBLES + "/{responsableId}";
    static final String ARCHIVE_PROCESS = PROCESS + "/archivar";
    static final String ARCHIVE_RESPONSIBLE = RESPONSIBLE + "/archivar";

    private final ProcessCatalog catalog;

    ApiProcessCatalogController(ProcessCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping(path = PROCESSES, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Page<Process>> processes(@PathVariable UUID empresaId, @RequestParam(required = false) State estado,
            @RequestParam(required = false) UUID responsableId, @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(catalog.processes(scope(empresaId, actor, request), estado, responsableId, pagina, tamano));
    }

    @GetMapping(path = PROCESS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Process> process(@PathVariable UUID empresaId, @PathVariable UUID procesoId,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(catalog.process(scope(empresaId, actor, request), procesoId));
    }

    @PostMapping(path = PROCESSES, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Process> createProcess(@PathVariable UUID empresaId, @Valid @RequestBody NewProcess input,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return created(catalog.createProcess(scope(empresaId, actor, request),
                new ProcessInput(input.nombre().strip(), input.descripcion(), input.responsableId())));
    }

    @PutMapping(path = PROCESS, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Process> updateProcess(@PathVariable UUID empresaId, @PathVariable UUID procesoId,
            @Valid @RequestBody EditProcess input, @AuthenticationPrincipal SessionIdentity actor,
            HttpServletRequest request) {
        return view(catalog.updateProcess(scope(empresaId, actor, request), procesoId, input.revisionEsperada(),
                new ProcessInput(input.nombre().strip(), input.descripcion(), input.responsableId())));
    }

    @PostMapping(path = ARCHIVE_PROCESS, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Process> archiveProcess(@PathVariable UUID empresaId, @PathVariable UUID procesoId,
            @Valid @RequestBody Archive input, @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(catalog.archiveProcess(scope(empresaId, actor, request), procesoId,
                input.revisionEsperada(), input.motivo().strip()));
    }

    @GetMapping(path = RESPONSIBLES, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Page<Responsible>> responsibles(@PathVariable UUID empresaId,
            @RequestParam(required = false) State estado, @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(catalog.responsibles(scope(empresaId, actor, request), estado, pagina, tamano));
    }

    @GetMapping(path = RESPONSIBLE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Responsible> responsible(@PathVariable UUID empresaId, @PathVariable UUID responsableId,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(catalog.responsible(scope(empresaId, actor, request), responsableId));
    }

    @PostMapping(path = RESPONSIBLES, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Responsible> createResponsible(@PathVariable UUID empresaId, @Valid @RequestBody NewResponsible input,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return created(catalog.createResponsible(scope(empresaId, actor, request),
                new ResponsibleInput(input.nombre().strip(), input.tipo(), input.membresiaId())));
    }

    @PutMapping(path = RESPONSIBLE, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Responsible> updateResponsible(@PathVariable UUID empresaId, @PathVariable UUID responsableId,
            @Valid @RequestBody EditResponsible input, @AuthenticationPrincipal SessionIdentity actor,
            HttpServletRequest request) {
        return view(catalog.updateResponsible(scope(empresaId, actor, request), responsableId, input.revisionEsperada(),
                new ResponsibleInput(input.nombre().strip(), input.tipo(), input.membresiaId())));
    }

    @PostMapping(path = ARCHIVE_RESPONSIBLE, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Responsible> archiveResponsible(@PathVariable UUID empresaId, @PathVariable UUID responsableId,
            @Valid @RequestBody Archive input, @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return view(catalog.archiveResponsible(scope(empresaId, actor, request), responsableId,
                input.revisionEsperada(), input.motivo().strip()));
    }

    private static Scope scope(UUID empresaId, SessionIdentity actor, HttpServletRequest request) {
        if (actor == null) {
            throw new org.springframework.security.access.AccessDeniedException("Acceso empresarial no autorizado.");
        }
        return new Scope(actor.usuarioId(), empresaId, ApiCorrelationFilter.id(request));
    }

    private static <T> ResponseEntity<T> view(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static <T> ResponseEntity<T> created(T body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body);
    }

    record NewProcess(@NotBlank @Size(max = 200) String nombre, String descripcion, UUID responsableId) {}
    record EditProcess(@NotNull @PositiveOrZero Long revisionEsperada, @NotBlank @Size(max = 200) String nombre,
                       String descripcion, UUID responsableId) {}
    record NewResponsible(@NotBlank @Size(max = 200) String nombre, @NotNull ResponsibleType tipo, UUID membresiaId) {}
    record EditResponsible(@NotNull @PositiveOrZero Long revisionEsperada, @NotBlank @Size(max = 200) String nombre,
                           @NotNull ResponsibleType tipo, UUID membresiaId) {}
    record Archive(@NotNull @PositiveOrZero Long revisionEsperada, @NotBlank @Size(max = 2000) String motivo) {}
}
