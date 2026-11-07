package software.mspi.gestion.internal.web;

import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import software.mspi.identidad.api.SessionIdentity;

@RestController
class ApiSessionController {

    static final String SESSION = "/api/v1/identidad/sesion";
    static final String CSRF = "/api/v1/identidad/csrf";

    private final AuthenticationManager authentication;
    private final HttpSessionSecurityContextRepository contexts;
    private final ChangeSessionIdAuthenticationStrategy fixation = new ChangeSessionIdAuthenticationStrategy();
    private final CsrfAuthenticationStrategy rotateCsrf;
    private final SecurityContextLogoutHandler logout = new SecurityContextLogoutHandler();

    ApiSessionController(AuthenticationManager authentication, HttpSessionSecurityContextRepository contexts,
                         HttpSessionCsrfTokenRepository csrf) {
        this.authentication = authentication;
        this.contexts = contexts;
        this.rotateCsrf = new CsrfAuthenticationStrategy(csrf);
    }

    @GetMapping(path = CSRF, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CsrfView> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CsrfView(token.getHeaderName(), token.getToken()));
    }

    @PostMapping(path = SESSION, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SessionView> login(@Valid @RequestBody LoginInput input,
                                      HttpServletRequest request, HttpServletResponse response) {
        var credentials = UsernamePasswordAuthenticationToken.unauthenticated(input.correo(), input.contrasena());
        credentials.setDetails(request.getRemoteAddr());
        Authentication authenticated = authentication.authenticate(credentials);
        fixation.onAuthentication(authenticated, request, response);
        rotateCsrf.onAuthentication(authenticated, request, response);

        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authenticated);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return current((SessionIdentity) authenticated.getPrincipal());
    }

    @GetMapping(path = SESSION, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SessionView> current(@AuthenticationPrincipal SessionIdentity identity) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new SessionView(identity.usuarioId(), identity.nombreVisible(), identity.expiraEn()));
    }

    @DeleteMapping(SESSION)
    ResponseEntity<Void> logout(Authentication identity, HttpServletRequest request, HttpServletResponse response) {
        logout.logout(request, response, identity);
        var cookie = request.getServletContext().getSessionCookieConfig();
        String path = cookie.getPath();
        if (path == null) {
            path = request.getContextPath().isEmpty() ? "/" : request.getContextPath();
        }
        var expired = ResponseCookie.from(cookie.getName(), "")
                .path(path).domain(cookie.getDomain()).maxAge(0)
                .secure(cookie.isSecure() || request.isSecure()).httpOnly(cookie.isHttpOnly()).build();
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, expired.toString()).build();
    }

    record LoginInput(@NotBlank @Email @Size(max = 254) String correo,
                      @NotEmpty @Size(max = 1024) String contrasena) {
        @Override
        public String toString() {
            return "LoginInput[redacted]";
        }
    }

    record SessionView(UUID usuarioId, String nombreVisible, Instant expiraEn) {}
    record CsrfView(String headerName, String token) {}
}
