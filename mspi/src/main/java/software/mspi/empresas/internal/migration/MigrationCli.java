package software.mspi.empresas.internal.migration;

import java.io.PrintStream;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.api.FlywayException;

/** Separate technical entry point; no HTTP endpoint or automatic Spring startup migration. */
public final class MigrationCli {

    private MigrationCli() {}

    public static void main(String[] args) {
        int status = run(args, System.getenv(), System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    public static int run(String[] args, Map<String, String> environment, PrintStream output, PrintStream error) {
        try {
            MigrationScope scope;
            if (args.length == 2 && ("prepare".equals(args[0]) || "upgrade".equals(args[0]))) {
                return new TenantPreparation().run(MigrationScope.tenant(UUID.fromString(args[1])),
                        "upgrade".equals(args[0]), environment, output, error);
            } else if (args.length == 1 && "common".equals(args[0])) {
                scope = MigrationScope.common();
            } else if (args.length == 2 && "tenant".equals(args[0])) {
                scope = MigrationScope.tenant(UUID.fromString(args[1]));
            } else {
                error.println("Uso: common | tenant <empresa-uuid> | prepare <empresa-uuid> | upgrade <empresa-uuid>");
                return 2;
            }

            var flyway = new SchemaMigrator().prepare(scope,
                    environment.get("MSPI_MIGRATION_JDBC_URL"),
                    environment.get("MSPI_MIGRATION_USER"),
                    environment.get("MSPI_MIGRATION_PASSWORD"));
            if (scope.isCommon()) {
                output.printf("Ámbito %s: %d migraciones aplicadas.%n", scope.schema(), flyway.migrate().migrationsExecuted);
            } else {
                try (var session = flyway.getConfiguration().getDataSource().getConnection()) {
                    if (!TenantMigrationLock.acquire(session)) {
                        error.println("Ya hay una preparación o migración empresarial en curso.");
                        return 1;
                    }
                    output.printf("Ámbito %s: %d migraciones aplicadas.%n", scope.schema(), flyway.migrate().migrationsExecuted);
                }
            }
            return 0;
        } catch (IllegalArgumentException | FlywayException | SQLException exception) {
            // Do not echo environment values, SQL statements or connection details.
            error.println("No se pudo completar el comando. Verifique el ámbito, las credenciales técnicas y la preparación del esquema.");
            return 1;
        }
    }
}
