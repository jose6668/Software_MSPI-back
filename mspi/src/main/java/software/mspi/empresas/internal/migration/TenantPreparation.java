package software.mspi.empresas.internal.migration;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

import org.flywaydb.core.api.FlywayException;

import static software.mspi.empresas.internal.migration.PreparationJournal.Result;
import static software.mspi.empresas.internal.migration.PreparationJournal.Step;

/** Coordinates the initial technical preparation outside ordinary application startup. */
final class TenantPreparation {

    int run(MigrationScope scope, Map<String, String> environment, PrintStream output, PrintStream error)
            throws SQLException {
        String url = environment.get("MSPI_MIGRATION_JDBC_URL");
        SchemaMigrator.requirePostgresUrl(url);
        String operator = environment.get("MSPI_PREPARATION_USER");
        String operatorPassword = environment.get("MSPI_PREPARATION_PASSWORD");
        if (missing(operator) || missing(operatorPassword)) {
            throw new IllegalArgumentException("Se requiere una credencial de aprovisionamiento externa.");
        }

        try (var connection = DriverManager.getConnection(url, operator, operatorPassword)) {
            if (!TenantMigrationLock.acquire(connection)) {
                error.println("Ya hay una preparación o migración empresarial en curso.");
                return 1;
            }
            connection.setAutoCommit(false);
            var journal = new PreparationJournal(connection, scope);
            journal.begin();
            var step = Step.APROVISIONAR;
            try {
                bootstrap(connection, scope);
                journal.advance(Step.CREDENCIALES);
                step = Step.CREDENCIALES;

                String migrator = environment.get("MSPI_MIGRATION_USER");
                String migrationPassword = environment.get("MSPI_MIGRATION_PASSWORD");
                String runtimePassword = environment.get("MSPI_PREPARATION_RUNTIME_PASSWORD");
                if (missing(migrator) || missing(migrationPassword) || missing(runtimePassword)) {
                    journal.finish(Result.ESPERA_CREDENCIALES);
                    output.printf("Intento %s: esquema y roles preparados. Configure las credenciales y repita con el mismo UUID.%n",
                            journal.attemptId());
                    return 3;
                }
                var flyway = new SchemaMigrator().prepare(scope, url, migrator, migrationPassword);
                journal.advance(Step.MIGRAR);
                step = Step.MIGRAR;
                flyway.migrate();

                journal.advance(Step.VERIFICAR);
                step = Step.VERIFICAR;
                flyway.validate();
                var installed = flyway.info().current();
                if (installed == null || installed.getVersion() == null
                        || !SchemaMigrator.TENANT_STRUCTURE_VERSION.equals(installed.getVersion().getVersion())) {
                    throw new IllegalStateException("La versión instalada no coincide con la soportada.");
                }
                TenantRuntimeVerifier.verify(scope, url, runtimePassword);
                journal.finish(Result.VERIFICADO);
                output.printf("Intento %s: estructura %s verificada. Empresa en preparación.%n",
                        journal.attemptId(), SchemaMigrator.TENANT_STRUCTURE_VERSION);
                return 0;
            } catch (SQLException | IOException | FlywayException | IllegalArgumentException | IllegalStateException failure) {
                error.printf("Intento %s: fallo en %s.%n", journal.attemptId(), step);
                connection.rollback();
                journal.finish(Result.FALLIDO);
                return 1;
            }
        }
    }

    private static void bootstrap(Connection connection, MigrationScope scope) throws IOException, SQLException {
        try (var statement = connection.prepareStatement("SELECT set_config('mspi.preparation_company', ?, true)")) {
            statement.setString(1, scope.companyId().toString());
            statement.execute();
        }
        try (var resource = TenantPreparation.class.getResourceAsStream("/db/provisioning/tenant-bootstrap.sql")) {
            if (resource == null) {
                throw new IOException("Falta el SQL de aprovisionamiento empresarial.");
            }
            try (var statement = connection.createStatement()) {
                statement.execute(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    private static boolean missing(String value) {
        return value == null || value.isEmpty();
    }
}
