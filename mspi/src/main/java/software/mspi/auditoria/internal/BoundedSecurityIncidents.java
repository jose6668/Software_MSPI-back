package software.mspi.auditoria.internal;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import software.mspi.auditoria.api.SecurityIncidents;

@Component
final class BoundedSecurityIncidents implements SecurityIncidents {
    private static final Logger LOG = LoggerFactory.getLogger("software.mspi.security.events");
    private static final long WINDOW_SECONDS = 60;
    private static final long EXAMPLES = 3;
    private static final long ALERT_AT = 10;

    private final Clock clock;
    private final Map<Type, Window> windows = new EnumMap<>(Type.class);

    @org.springframework.beans.factory.annotation.Autowired
    BoundedSecurityIncidents(ObjectProvider<Clock> clocks) {
        this(clocks.getIfAvailable(Clock::systemUTC));
    }

    BoundedSecurityIncidents(Clock clock) { this.clock = clock; }

    @Override
    public void record(Type type, UUID correlation) {
        var observation = count(type);
        long previous = observation.previous();
        if (previous > EXAMPLES) {
            LOG.info("seguridad clase=resumen evento={} total={} omitidos={} ventana_segundos={}",
                    type, previous, previous - EXAMPLES - (previous >= ALERT_AT ? 1 : 0), WINDOW_SECONDS);
        }
        long current = observation.current();
        if (current <= EXAMPLES) {
            if (current == 1 && (type == Type.AMENAZA_EVIDENCIA || type == Type.INSPECCION_NO_DISPONIBLE)) {
                LOG.warn("seguridad clase=alerta evento={} correlacion={}", type, correlation);
            } else {
                LOG.info("seguridad clase=muestra evento={} correlacion={}", type, correlation);
            }
        } else if (current == ALERT_AT) {
            LOG.warn("seguridad clase=alerta_frecuencia evento={} ocurrencias={} ventana_segundos={} correlacion={}",
                    type, current, WINDOW_SECONDS, correlation);
        }
    }

    private synchronized Observation count(Type type) {
        long minute = Math.floorDiv(clock.instant().getEpochSecond(), WINDOW_SECONDS);
        Window window = windows.get(type);
        long previous = 0;
        if (window == null || minute > window.minute) {
            previous = window == null ? 0 : window.count;
            window = new Window(minute);
            windows.put(type, window);
        }
        if (window.count < Long.MAX_VALUE) window.count++;
        return new Observation(window.count, previous);
    }

    private record Observation(long current, long previous) {}

    private static final class Window {
        private final long minute;
        private long count;
        Window(long minute) { this.minute = minute; }
    }
}
