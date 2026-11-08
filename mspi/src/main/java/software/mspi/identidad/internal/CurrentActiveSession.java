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
                || !isCurrent(identity)) {
            throw denied();
        }
        return identity.usuarioId();
    }

    @Override
    public boolean isCurrent(SessionIdentity identity) {
        return clock.instant().isBefore(identity.expiraEn())
                && jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM comun.usuarios
                    WHERE id = :id AND estado_cuenta = 'HABILITADA' AND version_credencial = :version
                )
                """)
                .param("id", identity.usuarioId())
                .param("version", identity.credentialVersion())
                .query(Boolean.class)
                .single();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Acceso no autorizado.");
    }
}
