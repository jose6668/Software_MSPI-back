package software.mspi.identidad.internal;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import software.mspi.identidad.api.EmailKey;
import software.mspi.identidad.api.LoginLimitException;
import software.mspi.identidad.api.SessionIdentity;

final class LocalAccountProvider implements AuthenticationProvider {

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final IdentitySettings settings;
    private final LoginAttempts attempts;
    private final Semaphore checks;
    private final String dummyHash;

    LocalAccountProvider(JdbcClient jdbc, PasswordEncoder encoder, IdentitySettings settings, Clock clock) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.settings = settings;
        this.clock = clock;
        this.attempts = new LoginAttempts(settings, clock);
        this.checks = new Semaphore(settings.concurrentChecks());
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    @Override
    public Authentication authenticate(Authentication request) {
        if (!checks.tryAcquire()) {
            throw new LoginLimitException();
        }
        try {
            String account = EmailKey.normalize(request.getName());
            String address = request.getDetails() instanceof String remote ? remote : "unknown";
            attempts.reserve(account, address);
            return verify(account, (String) request.getCredentials());
        } finally {
            checks.release();
        }
    }

    private Authentication verify(String accountKey, String password) {
        var account = jdbc.sql("""
                SELECT id, nombre_visible, hash_credencial FROM comun.usuarios
                WHERE clave_correo = :key AND estado_cuenta = 'HABILITADA'
                """)
                .param("key", accountKey)
                .query((row, index) -> new Account(row.getObject("id", UUID.class),
                        row.getString("nombre_visible"), row.getString("hash_credencial")))
                .optional();
        String hash = account.map(Account::hash).orElse(dummyHash);
        boolean matches;
        try {
            matches = encoder.matches(password, hash);
        } catch (IllegalArgumentException invalidHash) {
            // A legacy or damaged stored hash is an ordinary rejection to the client.
            encoder.matches(password, dummyHash);
            matches = false;
        }
        if (account.isEmpty() || !matches) {
            throw new BadCredentialsException("Credenciales inválidas.");
        }
        Account identity = account.orElseThrow();
        var principal = new SessionIdentity(identity.id(), identity.displayName(),
                clock.instant().plus(settings.sessionLifetime()));
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    }

    @Override
    public boolean supports(Class<?> type) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);
    }

    private record Account(UUID id, String displayName, String hash) {}
}
