package software.mspi.identidad.api;

import org.springframework.security.core.AuthenticationException;

public final class LoginLimitException extends AuthenticationException {
    public LoginLimitException() {
        super("Límite de autenticación alcanzado.");
    }
}
