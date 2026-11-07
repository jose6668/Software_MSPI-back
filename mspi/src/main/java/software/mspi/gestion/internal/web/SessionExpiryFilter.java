package software.mspi.gestion.internal.web;

import java.io.IOException;
import java.time.Clock;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;
import software.mspi.identidad.api.SessionIdentity;

final class SessionExpiryFilter extends OncePerRequestFilter {

    private final Clock clock;
    private final SecurityContextLogoutHandler logout = new SecurityContextLogoutHandler();

    SessionExpiryFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SessionIdentity identity
                && !clock.instant().isBefore(identity.expiraEn())) {
            logout.logout(request, response, authentication);
        }
        chain.doFilter(request, response);
    }
}
