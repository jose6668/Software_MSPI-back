package software.mspi.gestion.internal.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import software.mspi.identidad.api.AccountRecovery;

@RestController
@ConditionalOnProperty(name = "mspi.recovery.enabled", havingValue = "true")
class ApiRecoveryController {

    static final String REQUEST = "/api/v1/identidad/recuperacion";
    static final String CONFIRM = REQUEST + "/confirmar";

    private final AccountRecovery recovery;

    ApiRecoveryController(AccountRecovery recovery) {
        this.recovery = recovery;
    }

    @PostMapping(path = REQUEST, consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> request(@Valid @RequestBody Request input, HttpServletRequest request) {
        recovery.request(input.correo(), request.getRemoteAddr(), ApiCorrelationFilter.id(request));
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping(path = CONFIRM, consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> confirm(@Valid @RequestBody Confirmation input, HttpServletRequest request) {
        recovery.confirm(input.token(), input.contrasena(), request.getRemoteAddr());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    record Request(@NotBlank @Email @Size(max = 254) String correo) {
        @Override public String toString() { return "Request[redacted]"; }
    }

    record Confirmation(@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token,
                        @NotEmpty @Size(min = 15, max = 1024) String contrasena) {
        @Override public String toString() { return "Confirmation[redacted]"; }
    }
}
