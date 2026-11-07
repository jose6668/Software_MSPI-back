package software.mspi;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.mspi.empresas.internal.migration.MigrationCli;
import software.mspi.empresas.internal.migration.MigrationScope;
import software.mspi.empresas.internal.migration.SchemaMigrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class SchemaMigrationTests {

    @Autowired
    private TestDatabase database;

    @Test
    void installsCommonAndTwoTenantSchemasWithIndependentHistoryAndNoActivation() throws Exception {
        var first = database.reserveAndProvisionTenant();
        var second = database.reserveAndProvisionTenant();
        var firstFlyway = database.tenantFlyway(first);
        var secondFlyway = database.tenantFlyway(second);

        assertThat(firstFlyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(secondFlyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(firstFlyway.migrate().migrationsExecuted).isZero();
        assertThat(secondFlyway.migrate().migrationsExecuted).isZero();
        assertThat(database.commonFlyway().migrate().migrationsExecuted).isZero();

        var cliOutput = new ByteArrayOutputStream();
        assertThat(MigrationCli.run(new String[]{"tenant", first.scope().companyId().toString()}, Map.of(
                        "MSPI_MIGRATION_JDBC_URL", database.url(),
                        "MSPI_MIGRATION_USER", first.scope().migrationRole(),
                        "MSPI_MIGRATION_PASSWORD", first.migrationPassword()),
                new PrintStream(cliOutput, true, StandardCharsets.UTF_8), System.err)).isZero();
        assertThat(cliOutput.toString(StandardCharsets.UTF_8)).contains("0 migraciones aplicadas");

        for (var flyway : List.of(database.commonFlyway(), firstFlyway, secondFlyway)) {
            String expected = "comun".equals(flyway.getConfiguration().getDefaultSchema()) ? "6" : "1";
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo(expected);
            assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        }
        try (var connection = database.admin()) {
            for (var schema : List.of("comun", first.scope().schema(), second.scope().schema())) {
                assertThat(query(connection, "SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE success"))
                        .isEqualTo("comun".equals(schema) ? 6L : 1L);
            }
            assertThat(query(connection, "SELECT estado FROM comun.empresas WHERE id = '" + first.scope().companyId() + "'"))
                    .isEqualTo("EN_PREPARACION");
            assertThat(query(connection, "SELECT count(*) FROM pg_catalog.pg_tables WHERE schemaname = 'public' AND tablename = 'flyway_schema_history'"))
                    .isEqualTo(0L);
        }
    }

    @Test
    void enforcesOneImmutableOwnerAndLocalCompanyReferences() throws Exception {
        var tenant = migratedTenant();
        String schema = tenant.scope().schema();
        try (var connection = database.connect(tenant.scope().migrationRole(), tenant.migrationPassword())) {
            reject(connection, "23514", "UPDATE " + schema + ".contexto_empresa SET empresa_id = '" + UUID.randomUUID() + "'");
            reject(connection, "23505", "INSERT INTO " + schema + ".contexto_empresa(empresa_id) VALUES ('" + tenant.scope().companyId() + "')");
            try (var statement = connection.createStatement()) {
                // A test-only child demonstrates the FK pattern for future enterprise tables.
                statement.execute("CREATE TABLE " + schema + ".ownership_probe(id uuid PRIMARY KEY, empresa_id uuid NOT NULL REFERENCES "
                        + schema + ".contexto_empresa(empresa_id))");
                statement.execute("GRANT SELECT, INSERT ON " + schema + ".ownership_probe TO " + tenant.scope().runtimeRole());
            }
        }
        try (var connection = database.connect(tenant.scope().runtimeRole(), tenant.runtimePassword())) {
            reject(connection, "42501", "DELETE FROM " + schema + ".contexto_empresa");
            reject(connection, "42501", "UPDATE " + schema + ".contexto_empresa SET empresa_id = empresa_id");
            reject(connection, "23503", "INSERT INTO " + schema + ".ownership_probe VALUES ('" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "')");
            try (var statement = connection.createStatement()) {
                assertThat(statement.executeUpdate("INSERT INTO " + schema + ".ownership_probe VALUES ('"
                        + UUID.randomUUID() + "', '" + tenant.scope().companyId() + "')")).isEqualTo(1);
            }
        }
    }

    @Test
    void ordinaryCredentialsCannotPerformDdlEscalateOrReadFlywayHistory() throws Exception {
        var tenant = migratedTenant();
        String schema = tenant.scope().schema();
        try (var connection = database.connect(tenant.scope().runtimeRole(), tenant.runtimePassword())) {
            for (var sql : List.of(
                    "CREATE TABLE " + schema + ".forbidden(id integer)",
                    "CREATE TABLE public.forbidden(id integer)",
                    "CREATE TEMPORARY TABLE forbidden(id integer)",
                    "CREATE SCHEMA forbidden",
                    "SET ROLE " + tenant.scope().migrationRole(),
                    "SELECT * FROM " + schema + ".flyway_schema_history")) {
                reject(connection, "42501", sql);
            }
            assertUnprivileged(connection);
        }
        // Bypass our Java identity guard: PostgreSQL must still reject migration access.
        var unauthorized = Flyway.configure()
                .dataSource(database.url(), tenant.scope().runtimeRole(), tenant.runtimePassword())
                .schemas(schema).defaultSchema(schema).createSchemas(false)
                .locations("classpath:db/migration/tenant").load();
        assertThatThrownBy(unauthorized::migrate).isInstanceOf(FlywayException.class);
    }

    @Test
    void technicalIdentitiesAreLimitedToTheirScope() throws Exception {
        var first = migratedTenant();
        var second = migratedTenant();
        try (var connection = database.connect(first.scope().migrationRole(), first.migrationPassword())) {
            assertUnprivileged(connection);
            reject(connection, "42501", "SELECT * FROM " + second.scope().schema() + ".contexto_empresa");
            reject(connection, "42501", "SELECT * FROM comun.empresas");
            reject(connection, "42501", "CREATE SCHEMA unauthorized");
        }
        try (var connection = database.runtime()) {
            assertUnprivileged(connection);
            reject(connection, "42501", "SELECT * FROM " + first.scope().schema() + ".contexto_empresa");
            reject(connection, "42501", "CREATE TABLE comun.forbidden(id integer)");
        }
        for (var tenant : List.of(first, second)) {
            var other = tenant == first ? second : first;
            try (var connection = database.connect(tenant.scope().runtimeRole(), tenant.runtimePassword())) {
                assertThat(query(connection, "SELECT empresa_id FROM " + tenant.scope().schema() + ".contexto_empresa"))
                        .isEqualTo(tenant.scope().companyId());
                reject(connection, "42501", "SELECT * FROM " + other.scope().schema() + ".contexto_empresa");
                reject(connection, "42501", "INSERT INTO " + other.scope().schema()
                        + ".contexto_empresa(empresa_id) VALUES ('" + other.scope().companyId() + "')");
            }
        }
    }

    @Test
    void missingBootstrapDoesNotCreateSchemaAndCleanRemainsDisabled() throws Exception {
        var unknown = MigrationScope.tenant(UUID.randomUUID());
        assertThatThrownBy(() -> database.provision("tenant", "-v", "empresa_id=" + unknown.companyId()))
                .isInstanceOf(IllegalStateException.class);
        try (var connection = database.admin()) {
            assertThat(query(connection, "SELECT count(*) FROM pg_namespace WHERE nspname = '" + unknown.schema() + "'"))
                    .isEqualTo(0L);
        }
        var tenant = database.reserveAndProvisionTenant();
        try (var connection = database.admin(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + tenant.scope().schema());
        }
        var flyway = database.tenantFlyway(tenant);
        assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class);
        assertThatThrownBy(flyway::clean).isInstanceOf(FlywayException.class);
        try (var connection = database.admin()) {
            assertThat(query(connection, "SELECT count(*) FROM pg_namespace WHERE nspname = '" + tenant.scope().schema() + "'"))
                    .isEqualTo(0L);
        }
    }

    @Test
    void rollsBackTenantDdlWhenItsOwnerInsertFails() throws Exception {
        var tenant = database.reserveAndProvisionTenant();
        try (var connection = database.admin();
                var statement = connection.prepareStatement("DELETE FROM comun.empresas WHERE id = ?")) {
            statement.setObject(1, tenant.scope().companyId());
            statement.executeUpdate();
        }
        assertThatThrownBy(database.tenantFlyway(tenant)::migrate).isInstanceOf(FlywayException.class);
        try (var connection = database.admin()) {
            assertThat(query(connection, "SELECT count(*) FROM pg_catalog.pg_tables WHERE schemaname = '"
                    + tenant.scope().schema() + "' AND tablename = 'contexto_empresa'"))
                    .isEqualTo(0L);
            assertThat(query(connection, "SELECT count(*) FROM " + tenant.scope().schema() + ".flyway_schema_history WHERE success"))
                    .isEqualTo(0L);
        }
    }

    @Test
    void refusesToMigrateWhenAnAppliedChecksumChanged() throws Exception {
        var tenant = migratedTenant();
        try (var connection = database.connect(tenant.scope().migrationRole(), tenant.migrationPassword());
                var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE " + tenant.scope().schema() + ".flyway_schema_history SET checksum = checksum + 1 WHERE version = '1'");
        }
        assertThatThrownBy(database.tenantFlyway(tenant)::migrate).isInstanceOf(FlywayException.class);
    }

    @Test
    void technicalCliRejectsRuntimeCredentialsWithoutEchoingSecrets() {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        String secret = "only-for-leak-check-" + UUID.randomUUID();
        int status = MigrationCli.run(new String[]{"common"}, Map.of(
                        "MSPI_MIGRATION_JDBC_URL", database.url(),
                        "MSPI_MIGRATION_USER", MigrationScope.common().runtimeRole(),
                        "MSPI_MIGRATION_PASSWORD", secret),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8));
        assertThat(status).isEqualTo(1);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(error.toString(StandardCharsets.UTF_8)).doesNotContain(secret, database.url());
        assertThatThrownBy(() -> new SchemaMigrator().prepare(MigrationScope.common(),
                "jdbc:postgresql://operator:" + secret + "@localhost:1/mspi",
                MigrationScope.common().migrationRole(), secret))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(secret);
    }

    private TestDatabase.Tenant migratedTenant() throws Exception {
        var tenant = database.reserveAndProvisionTenant();
        database.tenantFlyway(tenant).migrate();
        return tenant;
    }

    private static void assertUnprivileged(Connection connection) throws SQLException {
        assertThat(query(connection, "SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls "
                + "FROM pg_roles WHERE rolname = current_user")).isEqualTo(false);
    }

    private static Object query(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getObject(1);
        }
    }

    private static void reject(Connection connection, String state, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute(sql))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo(state));
        }
    }
}
