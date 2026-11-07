package software.mspi.gestion.internal.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.UniqueElements;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import software.mspi.empresas.api.CompanyInvitations;
import software.mspi.empresas.api.CompanyRole;

@RestController
@ConditionalOnProperty(name = "mspi.invitations.enabled", havingValue = "true")
class ApiInvitationController {

    static final String ISSUE = "/api/v1/empresas/{empresaId}/invitaciones";
    static final String REVOKE = ISSUE + "/{invitacionId}/revocar";
    static final String ACCEPT = "/api/v1/invitaciones/aceptar";
    static final String ENROLL = "/api/v1/invitaciones/alta";
    private static final String TOKEN = "[A-Za-z0-9_-]{43}";

    private final CompanyInvitations invitations;

    ApiInvitationController(CompanyInvitations invitations) {
        this.invitations = invitations;
    }

    @PostMapping(path = ISSUE, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CompanyInvitations.Issued> issue(@PathVariable UUID empresaId, @Valid @RequestBody Issue input) {
        return created(invitations.issue(empresaId, input.correo(), input.roles()));
    }

    @PostMapping(REVOKE)
    ResponseEntity<Void> revoke(@PathVariable UUID empresaId, @PathVariable UUID invitacionId) {
        invitations.revoke(empresaId, invitacionId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping(path = ACCEPT, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CompanyInvitations.Accepted> accept(@Valid @RequestBody Accept input) {
        return created(invitations.accept(input.token()));
    }

    @PostMapping(path = ENROLL, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CompanyInvitations.Accepted> enroll(@Valid @RequestBody Enroll input) {
        return created(invitations.enroll(input.token(), input.nombreVisible().strip(), input.contrasena()));
    }

    private static <T> ResponseEntity<T> created(T body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body);
    }

    record Issue(@NotBlank @Email @Size(max = 254) String correo,
                 @NotEmpty @Size(max = 4) @UniqueElements List<@NotNull CompanyRole> roles) {
        @Override public String toString() { return "Issue[redacted]"; }
    }

    record Accept(@NotNull @Pattern(regexp = TOKEN) String token) {
        @Override public String toString() { return "Accept[redacted]"; }
    }

    record Enroll(@NotNull @Pattern(regexp = TOKEN) String token,
                  @NotBlank @Size(max = 150) String nombreVisible,
                  @NotEmpty @Size(max = 1024) String contrasena) {
        @Override public String toString() { return "Enroll[redacted]"; }
    }
}
