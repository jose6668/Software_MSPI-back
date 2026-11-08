package software.mspi.empresas.internal.persistence;

import java.time.Duration;

public record SqlResourceSettings(Duration statementTimeout, Duration lockTimeout,
        Duration idleTransactionTimeout, Duration transactionTimeout, int workMemKib,
        int commonConnections, Duration commonWait) {
    public SqlResourceSettings {
        if (!bounded(statementTimeout, 100, 30000) || !bounded(lockTimeout, 100, 5000)
                || lockTimeout.compareTo(statementTimeout) >= 0
                || !bounded(idleTransactionTimeout, 1000, 30000)
                || !bounded(transactionTimeout, 2000, 60000)
                || transactionTimeout.compareTo(statementTimeout) <= 0
                || workMemKib < 64 || workMemKib > 4096
                || commonConnections < 1 || commonConnections > 8 || !bounded(commonWait, 250, 5000)) {
            throw new IllegalArgumentException("Configure presupuestos SQL positivos, acotados y coherentes.");
        }
    }
    private static boolean bounded(Duration value, long minimum, long maximum) {
        return value != null && value.compareTo(Duration.ofMillis(minimum)) >= 0
                && value.compareTo(Duration.ofMillis(maximum)) <= 0;
    }
}
