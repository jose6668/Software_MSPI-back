package software.mspi.identidad.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import software.mspi.identidad.api.LoginLimitException;

final class AttemptLimiter {

    private final Duration window;
    private final int perAccount;
    private final int perAddress;
    private final int totalLimit;
    private final Clock clock;
    private final Map<String, Integer> accounts = new HashMap<>();
    private final Map<String, Integer> addresses = new HashMap<>();
    private Instant resetAt = Instant.MIN;
    private int total;

    AttemptLimiter(Duration window, int perAccount, int perAddress, int totalLimit, Clock clock) {
        this.window = window;
        this.perAccount = perAccount;
        this.perAddress = perAddress;
        this.totalLimit = totalLimit;
        this.clock = clock;
    }

    synchronized void reserve(String account, String address) {
        Instant now = clock.instant();
        if (!now.isBefore(resetAt)) {
            accounts.clear();
            addresses.clear();
            total = 0;
            resetAt = now.plus(window);
        }
        if (total >= totalLimit || accounts.getOrDefault(account, 0) >= perAccount
                || addresses.getOrDefault(address, 0) >= perAddress) {
            throw new LoginLimitException();
        }
        // The global limit bounds both maps, even for input with arbitrary identities.
        accounts.merge(account, 1, Integer::sum);
        addresses.merge(address, 1, Integer::sum);
        total++;
    }
}
