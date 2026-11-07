package software.mspi.gestion.internal.web;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;
import software.mspi.identidad.api.ActiveSession;
import software.mspi.identidad.api.SessionIdentity;

final class SessionExpiryFilter extends OncePerRequestFilter {

    private final ActiveSession sessions;
    private final ApiErrors errors;
    private final SecurityContextLogoutHandler logout = new SecurityContextLogoutHandler();

    SessionExpiryFilter(ActiveSession sessions, ApiErrors errors) {
        this.sessions = sessions;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof SessionIdentity identity) {
            try {
                if (!sessions.isCurrent(identity)) {
                    logout.logout(request, response, authentication);
                }
            } catch (DataAccessException failure) {
                errors.resolve(request, response, null, failure);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
