package software.mspi.auditoria.internal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static software.mspi.auditoria.api.SecurityIncidents.Type.*;

class BoundedSecurityIncidentsTests {
    private final Logger logger = (Logger) LoggerFactory.getLogger("software.mspi.security.events");
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();

    @BeforeEach void attach() {
        events.start();
        logger.addAppender(events);
    }

    @AfterEach void detach() {
        logger.detachAppender(events);
        events.stop();
    }

    @Test void aBurstEmitsThreeSamplesOneAlertAndARollup() {
        var clock = new TestClock();
        var incidents = new BoundedSecurityIncidents(clock);
        var id = UUID.randomUUID();
        for (int i = 0; i < 500; i++) incidents.record(LOGIN_FALLIDO, id);
        assertThat(events.list).hasSize(4);
        assertThat(events.list.get(3).getLevel()).isEqualTo(Level.WARN);
        assertThat(events.list.get(3).getFormattedMessage())
                .contains("alerta_frecuencia", "ocurrencias=10", "correlacion=" + id);
        clock.now = clock.now.plusSeconds(60);
        incidents.record(LOGIN_FALLIDO, id);
        assertThat(events.list).hasSize(6);
        assertThat(events.list.get(4).getFormattedMessage()).contains("total=500", "omitidos=496");
    }

    @Test void oneThreatAlertsEvenWhileAnotherCategoryIsThrottled() {
        var incidents = new BoundedSecurityIncidents(new TestClock());
        var id = UUID.randomUUID();
        for (int i = 0; i < 20; i++) incidents.record(CUOTA_EXCEDIDA, id);
        incidents.record(AMENAZA_EVIDENCIA, id);
        assertThat(events.list).hasSize(5);
        assertThat(events.list.get(4).getLevel()).isEqualTo(Level.WARN);
        assertThat(events.list.get(4).getFormattedMessage())
                .isEqualTo("seguridad clase=alerta evento=AMENAZA_EVIDENCIA correlacion=" + id);
    }

    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-08T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
