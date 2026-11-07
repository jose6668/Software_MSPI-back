package software.mspi.identidad.api;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.core.AuthenticatedPrincipal;

/** Identity retained in the server session, without credentials or company permissions. */
public record SessionIdentity(UUID usuarioId, String nombreVisible, Instant expiraEn)
        implements AuthenticatedPrincipal, Serializable {

    @Override
    public String getName() {
        return usuarioId.toString();
    }
}
