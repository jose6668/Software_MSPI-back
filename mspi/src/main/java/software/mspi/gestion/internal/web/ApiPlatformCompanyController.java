package software.mspi.gestion.internal.web;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.mspi.empresas.api.PlatformCompanies;

@RestController
class ApiPlatformCompanyController {

    static final String ROOT = "/api/v1/plataforma/empresas";

    private final PlatformCompanies platform;

    ApiPlatformCompanyController(PlatformCompanies platform) {
        this.platform = platform;
    }

    @GetMapping(path = ROOT, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PlatformCompanies.Page> list(@RequestParam(defaultValue = "0") @Min(0) int pagina,
                                                 @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano) {
        return view(platform.list(pagina, tamano));
    }

    @GetMapping(path = ROOT + "/{empresaId}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PlatformCompanies.Company> get(@PathVariable UUID empresaId) {
        return view(platform.get(empresaId));
    }

    @PostMapping(path = ROOT, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PlatformCompanies.Company> reserve(@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Reserve input, HttpServletRequest request) {
        var reservation = new PlatformCompanies.Reservation(input.nombre().strip(), input.descripcion(),
                input.zonaHoraria(), input.contactoInicial());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(platform.reserve(reservation, key, ApiCorrelationFilter.id(request)));
    }

    @PostMapping(path = ROOT + "/{empresaId}/activar", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PlatformCompanies.Company> activate(@PathVariable UUID empresaId,
            @Valid @RequestBody Revision input, HttpServletRequest request) {
        return view(platform.complete(empresaId, input.revisionEsperada(), false, ApiCorrelationFilter.id(request)));
    }

    @PostMapping(path = ROOT + "/{empresaId}/invitacion-inicial/reemitir", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PlatformCompanies.Company> reissue(@PathVariable UUID empresaId,
            @Valid @RequestBody Revision input, HttpServletRequest request) {
        return view(platform.complete(empresaId, input.revisionEsperada(), true, ApiCorrelationFilter.id(request)));
    }

    @PostMapping(path = ROOT + "/{empresaId}/suspender", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PlatformCompanies.Company> suspend(@PathVariable UUID empresaId,
            @Valid @RequestBody StateChange input, HttpServletRequest request) {
        return view(platform.changeAvailability(empresaId, input.revisionEsperada(), false, input.motivo().strip(),
                ApiCorrelationFilter.id(request)));
    }

    @PostMapping(path = ROOT + "/{empresaId}/reactivar", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PlatformCompanies.Company> reactivate(@PathVariable UUID empresaId,
            @Valid @RequestBody StateChange input, HttpServletRequest request) {
        return view(platform.changeAvailability(empresaId, input.revisionEsperada(), true, input.motivo().strip(),
                ApiCorrelationFilter.id(request)));
    }

    private static <T> ResponseEntity<T> view(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    record Reserve(@NotBlank @Size(max = 200) String nombre, String descripcion,
                   @NotBlank String zonaHoraria, @NotBlank @Email @Size(max = 254) String contactoInicial) {}

    record Revision(@NotNull @Positive Long revisionEsperada) {}

    record StateChange(@NotNull @Positive Long revisionEsperada, @NotBlank @Size(max = 1000) String motivo) {}
}
