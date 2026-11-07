package software.mspi.identidad.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mspi.identity")
record IdentitySettings(Duration sessionLifetime, Duration attemptWindow, int attemptsPerAccount,
                        int attemptsPerAddress, int totalAttempts, int concurrentChecks) {

    IdentitySettings {
        if (sessionLifetime == null || sessionLifetime.isNegative() || sessionLifetime.isZero()
                || attemptWindow == null || attemptWindow.isNegative() || attemptWindow.isZero()
                || attemptsPerAccount < 1 || attemptsPerAddress < 1 || totalAttempts < 1 || concurrentChecks < 1) {
            throw new IllegalArgumentException("Configure duraciones y límites de autenticación positivos.");
        }
    }
}
