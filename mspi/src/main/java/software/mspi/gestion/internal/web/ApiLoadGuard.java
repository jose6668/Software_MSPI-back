package software.mspi.gestion.internal.web;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.servlet.http.HttpSessionBindingEvent;
import jakarta.servlet.http.HttpSessionBindingListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
final class ApiLoadGuard {
    private final ApiLoadSettings settings;
    private final Clock clock;
    private final Map<String, Bucket> addresses = new HashMap<>();
    private final Semaphore requests;
    private final Semaphore evidence;
    private final Semaphore sessions;

    @org.springframework.beans.factory.annotation.Autowired
    ApiLoadGuard(ApiLoadSettings settings, ObjectProvider<Clock> clocks) {
        this(settings, clocks.getIfAvailable(Clock::systemUTC));
    }

    ApiLoadGuard(ApiLoadSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        requests = new Semaphore(settings.concurrentRequests());
        evidence = new Semaphore(settings.concurrentEvidence());
        sessions = new Semaphore(settings.maxSessions());
    }

    Lease enter(String address, boolean fileOperation) {
        reserveRate(address);
        if (!requests.tryAcquire()) throw capacity();
        if (fileOperation && !evidence.tryAcquire()) {
            requests.release();
            throw capacity();
        }
        return new Lease(() -> {
            if (fileOperation) evidence.release();
            requests.release();
        });
    }

    Lease newSession() {
        if (!sessions.tryAcquire()) throw capacity();
        return new Lease(sessions::release);
    }

    private synchronized void reserveRate(String address) {
        Instant now = clock.instant();
        Bucket bucket = addresses.get(address);
        if (bucket == null) {
            if (addresses.size() >= settings.trackedAddresses()) {
                addresses.values().removeIf(value -> !now.isBefore(value.until));
            }
            if (addresses.size() >= settings.trackedAddresses()) throw capacity();
            bucket = new Bucket(now.plus(settings.window()));
            addresses.put(address, bucket);
        } else if (!now.isBefore(bucket.until)) {
            bucket = new Bucket(now.plus(settings.window()));
            addresses.put(address, bucket);
        }
        if (bucket.count >= settings.requestsPerAddress()) {
            long seconds = Math.max(1, (java.time.Duration.between(now, bucket.until).toMillis() + 999) / 1000);
            throw new Rejected(HttpStatus.TOO_MANY_REQUESTS, "DEMASIADAS_SOLICITUDES", seconds);
        }
        bucket.count++;
    }

    static Rejected capacity() {
        return new Rejected(HttpStatus.SERVICE_UNAVAILABLE, "CAPACIDAD_AGOTADA", 1);
    }

    static final class Rejected extends RuntimeException {
        final HttpStatus status;
        final String code;
        final long retryAfter;
        Rejected(HttpStatus status, String code, long retryAfter) {
            super("Solicitud rechazada por limite de carga.");
            this.status = status;
            this.code = code;
            this.retryAfter = retryAfter;
        }
    }

    static final class Lease implements AutoCloseable, HttpSessionBindingListener {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Runnable release;
        Lease(Runnable release) { this.release = release; }
        @Override public void close() { if (closed.compareAndSet(false, true)) release.run(); }
        @Override public void valueUnbound(HttpSessionBindingEvent event) { close(); }
    }

    private static final class Bucket {
        final Instant until;
        int count;
        Bucket(Instant until) { this.until = until; }
    }
}
