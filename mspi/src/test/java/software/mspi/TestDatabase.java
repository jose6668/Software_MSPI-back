package software.mspi;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import software.mspi.empresas.internal.migration.MigrationScope;
import software.mspi.empresas.internal.migration.SchemaMigrator;

/** Privileged setup exists only in tests, outside ordinary application connections. */
final class TestDatabase {

    private final PostgreSQLContainer postgres;
    private final String commonMigrationPassword = UUID.randomUUID().toString();
    private final String commonRuntimePassword = UUID.randomUUID().toString();

    TestDatabase(PostgreSQLContainer postgres) throws Exception {
        this.postgres = postgres;
        provision("common");
        setPassword(MigrationScope.common().migrationRole(), commonMigrationPassword);
        setPassword(MigrationScope.common().runtimeRole(), commonRuntimePassword);
        commonFlyway().migrate();
    }

    org.flywaydb.core.Flyway commonFlyway() {
        return new SchemaMigrator().prepare(MigrationScope.common(), url(),
                MigrationScope.common().migrationRole(), commonMigrationPassword);
    }

    Tenant reserveAndProvisionTenant() throws Exception {
        var scope = MigrationScope.tenant(UUID.randomUUID());
        try (var connection = admin();
                var statement = connection.prepareStatement(
                        "INSERT INTO comun.empresas(id, nombre, zona_horaria, esquema_interno) VALUES (?, ?, ?, ?)")) {
            statement.setObject(1, scope.companyId());
            statement.setString(2, "Empresa ficticia");
            statement.setString(3, "America/Bogota");
            statement.setString(4, scope.schema());
            statement.executeUpdate();
        }
        provision("tenant", "-v", "empresa_id=" + scope.companyId());
        var tenant = new Tenant(scope, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        setPassword(scope.migrationRole(), tenant.migrationPassword());
        setPassword(scope.runtimeRole(), tenant.runtimePassword());
        return tenant;
    }

    org.flywaydb.core.Flyway tenantFlyway(Tenant tenant) {
        return new SchemaMigrator().prepare(tenant.scope(), url(),
                tenant.scope().migrationRole(), tenant.migrationPassword());
    }

    void provision(String script, String... variables) throws Exception {
        String path = "/tmp/mspi-" + script + ".sql";
        postgres.copyFileToContainer(MountableFile.forClasspathResource("db/provisioning/" + script + ".sql"), path);
        var command = new java.util.ArrayList<String>();
        command.addAll(java.util.List.of("psql", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName()));
        command.addAll(java.util.List.of(variables));
        command.addAll(java.util.List.of("-f", path));
        var result = postgres.execInContainer(command.toArray(String[]::new));
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("Fallo en bootstrap de prueba: " + result.getStderr());
        }
    }

    private void setPassword(String role, String password) throws SQLException {
        try (var connection = admin();
                var format = connection.prepareStatement("SELECT format('ALTER ROLE %I PASSWORD %L', ?::text, ?::text)")) {
            format.setString(1, role);
            format.setString(2, password);
            try (var result = format.executeQuery()) {
                result.next();
                try (var statement = connection.createStatement()) {
                    statement.execute(result.getString(1));
                }
            }
        }
    }

    String url() {
        return postgres.getJdbcUrl();
    }

    String runtimePassword() {
        return commonRuntimePassword;
    }

    Connection admin() throws SQLException {
        return connect(postgres.getUsername(), postgres.getPassword());
    }

    Connection runtime() throws SQLException {
        return connect(MigrationScope.common().runtimeRole(), commonRuntimePassword);
    }

    Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(url(), user, password);
    }

    record Tenant(MigrationScope scope, String migrationPassword, String runtimePassword) {}
}
