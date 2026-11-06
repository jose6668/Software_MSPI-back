package software.mspi.empresas.internal.tenancy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import software.mspi.empresas.internal.migration.MigrationScope;

/** Bounded pools keyed by technical identity/company; active units cannot be evicted. */
public final class TenantConnectionPools implements AutoCloseable {
    private final TenancyProperties limits;
    private final TenantSecretSource secrets;
    private final Clock clock;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private boolean closed;

    public TenantConnectionPools(TenancyProperties limits, TenantSecretSource secrets, Clock clock) {
        this.limits = limits; this.secrets = secrets; this.clock = clock;
    }

    synchronized Lease reserve(UUID company) {
        if (closed) throw new IllegalStateException("Los pools empresariales están cerrados.");
        Instant now = clock.instant();
        var iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.active == 0 && !entry.lastUsed.plus(limits.idleTtl()).isAfter(now)) {
                entry.source.close(); iterator.remove();
            }
        }
        var entry = entries.get(company);
        if (entry == null) {
            var scope = MigrationScope.tenant(company);
            var secret = secrets.load(company);
            if (!scope.runtimeRole().equals(secret.username())) {
                throw new IllegalArgumentException("La conexión debe usar el runtime de la empresa.");
            }
            if (entries.size() >= limits.maxPools()) {
                var idle = entries.values().stream().filter(e -> e.active == 0)
                        .min(Comparator.comparing(e -> e.lastUsed))
                        .orElseThrow(() -> new IllegalStateException("Capacidad empresarial ocupada; reintente después."));
                entries.remove(idle.company); idle.source.close();
            }
            var config = new HikariConfig();
            config.setJdbcUrl(secret.jdbcUrl());
            config.setUsername(secret.username());
            config.setPassword(secret.password());
            config.setSchema(scope.schema());
            config.setMaximumPoolSize(limits.connectionsPerPool());
            config.setMinimumIdle(0);
            config.setConnectionTimeout(limits.connectionTimeout().toMillis());
            config.setPoolName("mspi-tenant-" + company);
            entry = new Entry(company, scope, new HikariDataSource(config), now);
            entries.put(company, entry);
        }
        entry.active++;
        return new Lease(entry);
    }

    public synchronized int poolCount() { return entries.size(); }
    public synchronized int activeUnits() { return entries.values().stream().mapToInt(e -> e.active).sum(); }

    @Override public synchronized void close() {
        closed = true;
        entries.values().forEach(e -> e.source.close());
        entries.clear();
    }

    final class Lease implements AutoCloseable {
        private final Entry entry;
        private boolean released;
        Lease(Entry entry) { this.entry = entry; }

        Connection borrow() throws SQLException {
            var connection = entry.source.getConnection();
            try {
                reset(connection);
                try (var statement = connection.createStatement();
                        var result = statement.executeQuery("SELECT current_user, empresa_id FROM \""
                                + entry.scope.schema() + "\".contexto_empresa")) {
                    if (!result.next() || !entry.scope.runtimeRole().equals(result.getString(1))
                            || !entry.company.equals(result.getObject(2, UUID.class)) || result.next()) {
                        throw new SQLException("Contexto empresarial incompatible.", "28000");
                    }
                }
                return connection;
            } catch (SQLException | RuntimeException error) {
                entry.source.evictConnection(connection);
                connection.close();
                throw error;
            }
        }

        void release(Connection connection) throws SQLException {
            try {
                reset(connection);
            } catch (SQLException error) {
                entry.source.evictConnection(connection);
                throw error;
            } finally {
                connection.close();
            }
        }

        private void reset(Connection connection) throws SQLException {
            if (!connection.getAutoCommit()) connection.rollback();
            connection.setAutoCommit(true);
            connection.setReadOnly(false);
            try (var statement = connection.createStatement()) {
                statement.execute("RESET ROLE");
                statement.execute("RESET ALL");
            }
            connection.setSchema(entry.scope.schema());
            connection.clearWarnings();
        }

        @Override public void close() {
            synchronized (TenantConnectionPools.this) {
                if (!released) { released = true; entry.active--; entry.lastUsed = clock.instant(); }
            }
        }
    }

    private static final class Entry {
        final UUID company;
        final MigrationScope scope;
        final HikariDataSource source;
        Instant lastUsed;
        int active;
        Entry(UUID company, MigrationScope scope, HikariDataSource source, Instant now) {
            this.company = company; this.scope = scope; this.source = source; this.lastUsed = now;
        }
    }
}
