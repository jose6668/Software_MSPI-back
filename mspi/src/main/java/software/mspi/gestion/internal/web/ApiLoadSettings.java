package software.mspi.gestion.internal.web;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mspi.load")
record ApiLoadSettings(Duration window, int requestsPerAddress, int loginRequestsPerAddress,
                       int requestsPerAccount, int trackedAddresses,
                       int concurrentRequests, int concurrentEvidence, int maxSessions,
                       int jsonBytes, int multipartBytes) {
    ApiLoadSettings {
        if (window == null || window.compareTo(Duration.ofSeconds(1)) < 0
                || window.compareTo(Duration.ofMinutes(1)) > 0 || requestsPerAddress < 1
                || loginRequestsPerAddress < 1 || requestsPerAccount < 1
                || trackedAddresses < 1 || trackedAddresses > 65536
                || concurrentRequests < 1 || concurrentRequests > 256
                || concurrentEvidence < 1 || concurrentEvidence > concurrentRequests
                || maxSessions < 1 || maxSessions > 10000
                || jsonBytes < 1 || jsonBytes > multipartBytes || multipartBytes > 10016384) {
            throw new IllegalArgumentException("Configure limites de carga positivos y acotados.");
        }
    }
}
