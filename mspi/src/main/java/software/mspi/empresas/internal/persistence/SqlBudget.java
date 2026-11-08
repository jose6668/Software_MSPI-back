package software.mspi.empresas.internal.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.regex.Pattern;
import com.zaxxer.hikari.HikariConfig;

/** Runtime-only session policy, reapplied after each enterprise RESET ALL and common checkout. */
public final class SqlBudget {
    private static final Pattern URL_OVERRIDE = Pattern.compile(
            "[?&](connectTimeout|socketTimeout|cancelSignalTimeout|options)=", Pattern.CASE_INSENSITIVE);
    private final SqlResourceSettings settings;
    public SqlBudget(SqlResourceSettings settings) { this.settings = settings; }
    public SqlResourceSettings settings() { return settings; }

    public void configureNetwork(HikariConfig config) {
        if (config.getJdbcUrl() == null || !config.getJdbcUrl().startsWith("jdbc:postgresql:")
                || URL_OVERRIDE.matcher(config.getJdbcUrl()).find()) {
            throw new IllegalArgumentException("Use una URL PostgreSQL sin sobrescribir los presupuestos de red.");
        }
        config.setValidationTimeout(Math.min(1000, config.getConnectionTimeout()));
        config.addDataSourceProperty("connectTimeout", "3");
        config.addDataSourceProperty("socketTimeout", Long.toString((settings.statementTimeout().toMillis() + 2999) / 1000));
        config.addDataSourceProperty("cancelSignalTimeout", "2");
        config.addDataSourceProperty("tcpKeepAlive", "true");
    }

    public void apply(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT set_config('statement_timeout', ?, false), set_config('lock_timeout', ?, false),
                       set_config('idle_in_transaction_session_timeout', ?, false),
                       set_config('transaction_timeout', ?, false), set_config('work_mem', ?, false),
                       set_config('max_parallel_workers_per_gather', '0', false),
                       pg_size_bytes(current_setting('temp_file_limit'))
                """)) {
            statement.setString(1, settings.statementTimeout().toMillis() + "ms");
            statement.setString(2, settings.lockTimeout().toMillis() + "ms");
            statement.setString(3, settings.idleTransactionTimeout().toMillis() + "ms");
            statement.setString(4, settings.transactionTimeout().toMillis() + "ms");
            statement.setString(5, settings.workMemKib() + "kB");
            try (var result = statement.executeQuery()) {
                if (!result.next() || result.getLong(7) < 1 || result.getLong(7) > 67108864) {
                    throw new SQLException("El operador debe aplicar los presupuestos del rol runtime.", "53400");
                }
            }
        }
    }
}
