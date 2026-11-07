package software.mspi.identidad.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import software.mspi.identidad.api.LoginLimitException;

final class LoginAttempts {

    private final IdentitySettings settings;
    private final Clock clock;
    private final Map<String, Integer> accounts = new HashMap<>();
    private final Map<String, Integer> addresses = new HashMap<>();
    private Instant resetAt = Instant.MIN;
    private int total;

    LoginAttempts(IdentitySettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    synchronized void reserve(String account, String address) {
        Instant now = clock.instant();
        if (!now.isBefore(resetAt)) {
            accounts.clear();
            addresses.clear();
            total = 0;
            resetAt = now.plus(settings.attemptWindow());
        }
        if (total >= settings.totalAttempts() || accounts.getOrDefault(account, 0) >= settings.attemptsPerAccount()
                || addresses.getOrDefault(address, 0) >= settings.attemptsPerAddress()) {
            throw new LoginLimitException();
        }
        // The global limit bounds both maps, even for input with arbitrary identities.
        accounts.merge(account, 1, Integer::sum);
        addresses.merge(address, 1, Integer::sum);
        total++;
    }
}
