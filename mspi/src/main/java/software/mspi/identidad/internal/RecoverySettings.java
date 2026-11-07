package software.mspi.identidad.internal;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mspi.recovery")
record RecoverySettings(Duration validity, URI resetUrl, String from, int minPasswordLength, Duration attemptWindow,
                        int attemptsPerKey, int attemptsPerAddress, int totalAttempts, int concurrentChecks,
                        int queueCapacity) {

    RecoverySettings {
        if (validity == null || validity.toSeconds() < 1 || validity.toSeconds() > Integer.MAX_VALUE
                || attemptWindow == null || attemptWindow.isNegative() || attemptWindow.isZero()
                || attemptsPerKey < 1 || attemptsPerAddress < 1 || totalAttempts < 1
                || concurrentChecks < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("Configure la vigencia, los límites y la cola de recuperación.");
        }
        if (resetUrl == null || !"https".equalsIgnoreCase(resetUrl.getScheme()) || resetUrl.getHost() == null
                || resetUrl.getRawUserInfo() != null || resetUrl.getRawFragment() != null || resetUrl.getRawQuery() != null) {
            throw new IllegalArgumentException("Configure una URL HTTPS de recuperación sin consulta, fragmento ni credenciales.");
        }
        if (from == null || !from.matches("[^\\s@<>]+@[^\\s@<>]+") || minPasswordLength < 1 || minPasswordLength > 1024) {
            throw new IllegalArgumentException("Configure el remitente y la longitud mínima de recuperación.");
        }
    }
}
