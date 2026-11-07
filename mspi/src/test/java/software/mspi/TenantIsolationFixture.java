package software.mspi;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import software.mspi.empresas.api.TenantAccessPolicy;
import software.mspi.empresas.internal.tenancy.*;

final class TenantIsolationFixture {
    static final UUID SHARED_ID = UUID.fromString("4a1e2026-0000-4000-8000-000000000001");
    final UUID actor = UUID.randomUUID();
    final UUID otherActor = UUID.randomUUID();
    final TestDatabase database;
    final TestDatabase.Tenant first;
    final TestDatabase.Tenant second;
    final TestDatabase.Tenant third;
    final MockEnvironment environment = new MockEnvironment();
    final Map<UUID, Set<UUID>> grants = new ConcurrentHashMap<>();
    final AtomicInteger secretLoads = new AtomicInteger();
    final MutableClock clock = new MutableClock();

    TenantIsolationFixture(TestDatabase database) throws Exception {
        this.database = database;
        grants.put(actor, ConcurrentHashMap.newKeySet());
        grants.put(otherActor, ConcurrentHashMap.newKeySet());
        first = create("A", true);
        second = create("B", true);
        third = create("C", true);
        grants.get(otherActor).add(first.scope().companyId());
    }

    TestDatabase.Tenant create(String value, boolean probe) throws Exception {
        var tenant = database.reserveAndProvisionTenant();
        database.tenantFlyway(tenant).migrate();
        try (var connection = database.admin();
                var statement = connection.prepareStatement(
                        "UPDATE comun.empresas SET estado='ACTIVA', version_estructura='3' WHERE id=?")) {
            statement.setObject(1, tenant.scope().companyId());
            statement.executeUpdate();
        }
        if (probe) {
            try (var connection = database.connect(tenant.scope().migrationRole(), tenant.migrationPassword());
                    var statement = connection.createStatement()) {
                String schema = tenant.scope().schema();
                statement.execute("CREATE TABLE " + schema + ".isolation_probe (id uuid PRIMARY KEY, empresa_id uuid NOT NULL REFERENCES "
                        + schema + ".contexto_empresa(empresa_id), valor text NOT NULL, revision bigint NOT NULL DEFAULT 0)");
                statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON " + schema + ".isolation_probe TO " + tenant.scope().runtimeRole());
                try (var insert = connection.prepareStatement("INSERT INTO " + schema + ".isolation_probe(id, empresa_id, valor) VALUES (?, ?, ?)")) {
                    insert.setObject(1, SHARED_ID); insert.setObject(2, tenant.scope().companyId());
                    insert.setString(3, value); insert.executeUpdate();
                }
            }
        }
        String prefix = "mspi.tenancy.connections." + tenant.scope().companyId().toString().replace("-", "") + ".";
        environment.setProperty(prefix + "jdbc-url", database.url());
        environment.setProperty(prefix + "username", tenant.scope().runtimeRole());
        environment.setProperty(prefix + "password", tenant.runtimePassword());
        grants.get(actor).add(tenant.scope().companyId());
        return tenant;
    }

    TenantAccessPolicy policy() {
        return (user, company, permission) -> {
            if (!grants.getOrDefault(user, Set.of()).contains(company)) throw new AccessDeniedException("Acceso no autorizado.");
        };
    }

    TenantSecretSource secrets() {
        var delegate = new EnvironmentTenantSecretSource(environment);
        return company -> { secretLoads.incrementAndGet(); return delegate.load(company); };
    }

    void metadata(TestDatabase.Tenant tenant, String state, String version) throws SQLException {
        try (var connection = database.admin();
                var statement = connection.prepareStatement("UPDATE comun.empresas SET estado=?, version_estructura=? WHERE id=?")) {
            statement.setString(1, state); statement.setString(2, version);
            statement.setObject(3, tenant.scope().companyId()); statement.executeUpdate();
        }
    }

    static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-11-06T12:00:00Z");
        synchronized void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public synchronized Instant instant() { return instant; }
    }
}
