package software.mspi.gestion.internal.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static software.mspi.gestion.internal.web.ApiLoadGuard.Traffic.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiLoadGuardTests {
    static ApiLoadSettings settings(int rate, int addresses, int concurrent, int files, int sessions) {
        return new ApiLoadSettings(Duration.ofMinutes(1), rate, rate, rate, addresses, concurrent, files, sessions, 1024, 10016384);
    }

    @Test
    void rateIsPerAddressAndReturnsAfterTheWindow() {
        var clock = new TestClock();
        var guard = new ApiLoadGuard(settings(2, 2, 2, 1, 1), clock);
        try (var first = guard.enter(ANONYMOUS, "A", false)) {}
        try (var second = guard.enter(ANONYMOUS, "A", false)) {}
        assertThatThrownBy(() -> guard.enter(ANONYMOUS, "A", false)).isInstanceOfSatisfying(ApiLoadGuard.Rejected.class,
                error -> { assertThat(error.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(error.retryAfter).isEqualTo(60); });
        try (var other = guard.enter(ANONYMOUS, "B", false)) {}
        clock.advance();
        try (var restored = guard.enter(ANONYMOUS, "A", false)) {}
    }

    @Test
    void addressMemoryIsBoundedWithoutEvictingActiveBudgets() {
        var clock = new TestClock();
        var guard = new ApiLoadGuard(settings(3, 2, 2, 1, 1), clock);
        try (var first = guard.enter(ANONYMOUS, "A", false)) {}
        try (var second = guard.enter(ANONYMOUS, "B", false)) {}
        assertThatThrownBy(() -> guard.enter(ANONYMOUS, "C", false)).isInstanceOfSatisfying(ApiLoadGuard.Rejected.class,
                error -> assertThat(error.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        try (var stillAvailable = guard.enter(ANONYMOUS, "A", false)) {}
        clock.advance();
        try (var recovered = guard.enter(ANONYMOUS, "C", false)) {}
    }

    @Test
    void fileSaturationKeepsOrdinaryCapacityAndDoesNotLeakPermits() {
        var guard = new ApiLoadGuard(settings(100, 2, 2, 1, 1), Clock.systemUTC());
        try (var file = guard.enter(ACCOUNT, "A", true)) {
            assertThatThrownBy(() -> guard.enter(ACCOUNT, "A", true)).isInstanceOf(ApiLoadGuard.Rejected.class);
            try (var ordinary = guard.enter(ACCOUNT, "A", false)) {
                assertThatThrownBy(() -> guard.enter(ACCOUNT, "A", false)).isInstanceOf(ApiLoadGuard.Rejected.class);
            }
            try (var recovered = guard.enter(ACCOUNT, "A", false)) {}
        }
        try (var recovered = guard.enter(ACCOUNT, "A", true)) {}
    }

    @Test
    void simultaneousCallersCannotExceedTheGlobalBudget() throws Exception {
        var guard = new ApiLoadGuard(settings(100, 2, 3, 1, 1), Clock.systemUTC());
        var executor = Executors.newFixedThreadPool(12);
        var attempted = new CountDownLatch(12);
        var release = new CountDownLatch(1);
        var admitted = new AtomicInteger();
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 12; i++) futures.add(executor.submit(() -> {
                ApiLoadGuard.Lease lease = null;
                try { lease = guard.enter(ACCOUNT, "A", false); admitted.incrementAndGet(); }
                catch (ApiLoadGuard.Rejected expected) {}
                finally { attempted.countDown(); }
                if (lease != null) {
                    try { release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    finally { lease.close(); }
                }
            }));
            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(admitted.get()).isEqualTo(3);
            release.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
            try (var recovered = guard.enter(ACCOUNT, "A", false)) {}
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void exhaustedPublicTrafficLeavesRateAndConcurrencyForKnownAccounts() {
        var guard = new ApiLoadGuard(settings(2, 1, 4, 1, 1), Clock.systemUTC());
        try (var a = guard.enter(ANONYMOUS, "shared", false);
             var b = guard.enter(ANONYMOUS, "shared", false);
             var c = guard.enter(LOGIN, "shared", false)) {
            assertThatThrownBy(() -> guard.enter(ANONYMOUS, "shared", false))
                    .isInstanceOfSatisfying(ApiLoadGuard.Rejected.class,
                            error -> assertThat(error.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
            assertThatThrownBy(() -> guard.enter(LOGIN, "shared", false))
                    .isInstanceOfSatisfying(ApiLoadGuard.Rejected.class,
                            error -> assertThat(error.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
            try (var d = guard.enter(ACCOUNT, "account", false)) {}
        }
        try (var d = guard.enter(ACCOUNT, "account", false)) {}
        assertThatThrownBy(() -> guard.enter(ACCOUNT, "account", false))
                .isInstanceOfSatisfying(ApiLoadGuard.Rejected.class,
                        error -> assertThat(error.status).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void sessionLeaseCanBeReleasedOnlyOnce() {
        var guard = new ApiLoadGuard(settings(100, 2, 2, 1, 1), Clock.systemUTC());
        var session = guard.newSession();
        assertThatThrownBy(guard::newSession).isInstanceOf(ApiLoadGuard.Rejected.class);
        session.close();
        session.close();
        try (var next = guard.newSession()) {
            assertThatThrownBy(guard::newSession).isInstanceOf(ApiLoadGuard.Rejected.class);
        }
    }

    @Test
    void unsafeConfigurationsAreRejected() {
        assertThatThrownBy(() -> settings(0, 2, 2, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(2, 0, 2, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(2, 2, 2, 3, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(2, 2, 2, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-08T00:00:00Z");
        void advance() { now = now.plusSeconds(60); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
