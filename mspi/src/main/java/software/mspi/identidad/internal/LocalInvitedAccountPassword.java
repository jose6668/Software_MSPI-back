package software.mspi.identidad.internal;

import java.util.concurrent.Semaphore;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import software.mspi.identidad.api.InvitedAccountPassword;
import software.mspi.identidad.api.PasswordPolicy;
import software.mspi.identidad.api.LoginLimitException;

@Service
final class LocalInvitedAccountPassword implements InvitedAccountPassword {

    private final PasswordEncoder encoder;
    private final Semaphore checks;

    LocalInvitedAccountPassword(PasswordEncoder encoder, IdentitySettings settings) {
        this.encoder = encoder;
        this.checks = new Semaphore(settings.concurrentChecks());
    }

    @Override
    public String encode(String rawPassword) {
        if (!PasswordPolicy.accepts(rawPassword, PasswordPolicy.MIN_LENGTH)) {
            throw new IllegalArgumentException("La contrasena no cumple la politica.");
        }
        if (!checks.tryAcquire()) {
            throw new LoginLimitException();
        }
        try {
            return encoder.encode(rawPassword);
        } finally {
            checks.release();
        }
    }
}
