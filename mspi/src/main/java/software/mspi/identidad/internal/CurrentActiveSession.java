package software.mspi.identidad.internal;

import java.time.Clock;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import software.mspi.identidad.api.ActiveSession;
import software.mspi.identidad.api.SessionIdentity;

@Service
final class CurrentActiveSession implements ActiveSession {

    private final JdbcClient jdbc;
    private final Clock clock;

    CurrentActiveSession(JdbcClient jdbc, ObjectProvider<Clock> clocks) {
        this.jdbc = jdbc;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    @Override
    public UUID requireActiveUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof SessionIdentity identity)
                || !clock.instant().isBefore(identity.expiraEn())) {
            throw denied();
        }

        boolean enabled = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM comun.usuarios WHERE id = :id AND estado_cuenta = 'HABILITADA')
                """)
                .param("id", identity.usuarioId())
                .query(Boolean.class)
                .single();
        if (!enabled) {
            throw denied();
        }
        return identity.usuarioId();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Acceso no autorizado.");
    }
}
