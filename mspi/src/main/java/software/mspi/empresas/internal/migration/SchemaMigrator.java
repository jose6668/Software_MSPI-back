package software.mspi.empresas.internal.migration;

import java.util.HashMap;
import java.util.Objects;
import java.util.regex.Pattern;

import org.flywaydb.core.Flyway;

/** Invoked by the technical process, not registered as a Spring application bean. */
public final class SchemaMigrator {

    public static final String TENANT_STRUCTURE_VERSION = "1";

    private static final Pattern URL_CREDENTIAL = Pattern.compile(
            "[?&](user|password|sslpassword)=|^jdbc:postgresql://[^/]*@", Pattern.CASE_INSENSITIVE);

    public Flyway prepare(MigrationScope scope, String jdbcUrl, String username, String password) {
        Objects.requireNonNull(scope, "scope");
        requirePostgresUrl(jdbcUrl);
        if (!scope.migrationRole().equals(username) || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Se requieren credenciales de migración del ámbito seleccionado.");
        }

        var placeholders = new HashMap<String, String>();
        placeholders.put("runtime_role", scope.runtimeRole());
        if (!scope.isCommon()) {
            placeholders.put("empresa_id", scope.companyId().toString());
        }

        return Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .defaultSchema(scope.schema())
                .schemas(scope.schema())
                .locations("classpath:db/migration/" + (scope.isCommon() ? "common" : "tenant"))
                .placeholders(placeholders)
                .createSchemas(false)
                .cleanDisabled(true)
                .failOnMissingLocations(true)
                .validateMigrationNaming(true)
                .validateOnMigrate(true)
                .load();
    }

    static void requirePostgresUrl(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql:")
                || URL_CREDENTIAL.matcher(jdbcUrl).find()) {
            throw new IllegalArgumentException("Se requiere una URL PostgreSQL sin credenciales incorporadas.");
        }
    }
}
