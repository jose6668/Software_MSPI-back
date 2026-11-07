package software.mspi.gestion.internal.web;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.mspi.empresas.api.CompanyAdministration;
import software.mspi.empresas.api.CompanyRole;

@RestController
class ApiCompanyAdministrationController {

    private static final String ROOT = "/api/v1/empresas/{empresaId}";
    static final String CONFIGURATION = ROOT + "/configuracion";
    static final String MEMBERS = ROOT + "/membresias";
    static final String RESPONSIBLES = ROOT + "/responsables";
    static final String ROLES = MEMBERS + "/{membresiaId}/roles";
    static final String REVOKE = MEMBERS + "/{membresiaId}/revocar";

    private final CompanyAdministration administration;

    ApiCompanyAdministrationController(CompanyAdministration administration) {
        this.administration = administration;
    }

    @GetMapping(path = CONFIGURATION, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CompanyAdministration.Configuration> configuration(@PathVariable UUID empresaId) {
        return view(administration.configuration(empresaId));
    }

    @PutMapping(path = CONFIGURATION, consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> configure(@PathVariable UUID empresaId, @Valid @RequestBody Configuration input,
                                   HttpServletRequest request) {
        var configuration = new CompanyAdministration.Configuration(input.nombre().strip(), input.descripcion(),
                input.zonaHoraria(), input.revisionEsperada());
        administration.configure(empresaId, configuration, ApiCorrelationFilter.id(request));
        return noContent();
    }

    @GetMapping(path = MEMBERS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CompanyAdministration.Page<CompanyAdministration.Member>> members(@PathVariable UUID empresaId,
            @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano) {
        return view(administration.members(empresaId, pagina, tamano));
    }

    @GetMapping(path = ApiInvitationController.ISSUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CompanyAdministration.Page<CompanyAdministration.Invitation>> invitations(@PathVariable UUID empresaId,
            @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano) {
        return view(administration.invitations(empresaId, pagina, tamano));
    }

    @GetMapping(path = RESPONSIBLES, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CompanyAdministration.Page<CompanyAdministration.Responsible>> responsibles(@PathVariable UUID empresaId,
            @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano) {
        return view(administration.responsibles(empresaId, pagina, tamano));
    }

    @PutMapping(path = ROLES, consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> roles(@PathVariable UUID empresaId, @PathVariable UUID membresiaId,
                               @Valid @RequestBody RoleChange input, HttpServletRequest request) {
        administration.changeRoles(empresaId, membresiaId, input.revisionEsperada(), input.roles(),
                input.motivo().strip(), ApiCorrelationFilter.id(request));
        return noContent();
    }

    @PostMapping(path = REVOKE, consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> revoke(@PathVariable UUID empresaId, @PathVariable UUID membresiaId,
                                @Valid @RequestBody Revocation input, HttpServletRequest request) {
        administration.revoke(empresaId, membresiaId, input.revisionEsperada(), input.motivo().strip(),
                ApiCorrelationFilter.id(request));
        return noContent();
    }

    private static <T> ResponseEntity<T> view(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    record Configuration(@NotNull @Positive Long revisionEsperada, @NotBlank @Size(max = 200) String nombre,
                         String descripcion, @NotBlank String zonaHoraria) {}

    record RoleChange(@NotNull @Positive Long revisionEsperada, @NotBlank @Size(max = 1000) String motivo,
                      @NotEmpty @Size(max = 4) @UniqueElements List<@NotNull CompanyRole> roles) {}

    record Revocation(@NotNull @Positive Long revisionEsperada, @NotBlank @Size(max = 1000) String motivo) {}
}
