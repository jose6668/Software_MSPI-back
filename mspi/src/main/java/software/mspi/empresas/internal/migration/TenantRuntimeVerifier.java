package software.mspi.empresas.internal.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Checks the installed owner and effective privileges with the ordinary tenant role. */
public final class TenantRuntimeVerifier {

    private TenantRuntimeVerifier() {}

    public static void verify(MigrationScope scope, String url, String password) throws SQLException {
        String schema = scope.schema();
        String contextTable = schema + ".contexto_empresa";
        try (var connection = DriverManager.getConnection(url, scope.runtimeRole(), password);
                var statement = connection.prepareStatement("""
                        SELECT current_user, empresa_id,
                            pg_get_userbyid((SELECT relowner FROM pg_catalog.pg_class WHERE oid = context.tableoid)),
                            has_table_privilege(tableoid, 'INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER,REFERENCES'),
                            has_any_column_privilege(tableoid, 'INSERT,UPDATE,REFERENCES'),
                            has_table_privilege(?::regclass, 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER,REFERENCES'),
                            has_any_column_privilege(?::regclass, 'SELECT,INSERT,UPDATE,REFERENCES'),
                            has_database_privilege(current_database(), 'CREATE,TEMP')
                                OR has_schema_privilege('comun', 'USAGE')
                                OR EXISTS (
                                    SELECT 1 FROM pg_catalog.pg_namespace n
                                    WHERE has_schema_privilege(n.oid, 'CREATE')
                                        OR (starts_with(n.nspname, 'emp_') AND n.nspname <> ?
                                            AND has_schema_privilege(n.oid, 'USAGE'))
                                )
                                OR EXISTS (
                                    SELECT 1 FROM pg_catalog.pg_roles r
                                    WHERE r.rolname = current_user AND (
                                        r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolinherit
                                        OR r.rolreplication OR r.rolbypassrls OR EXISTS (
                                            SELECT 1 FROM pg_catalog.pg_auth_members m WHERE m.member = r.oid
                                        )
                                    )
                                )
                        FROM %s AS context
                        """.formatted(contextTable))) {
            statement.setString(1, schema + ".flyway_schema_history");
            statement.setString(2, schema + ".flyway_schema_history");
            statement.setString(3, schema);
            try (var result = statement.executeQuery()) {
                if (!result.next() || !scope.runtimeRole().equals(result.getString(1))
                        || !scope.companyId().equals(result.getObject(2))
                        || !scope.migrationRole().equals(result.getString(3))
                        || result.getBoolean(4) || result.getBoolean(5) || result.getBoolean(6) || result.getBoolean(7)
                        || result.getBoolean(8)
                        || result.next()) {
                    throw new IllegalStateException("Propietario o permisos empresariales incompatibles.");
                }
            }
            verifyCatalogGrants(connection, scope);
        }
    }

    private static void verifyCatalogGrants(Connection connection, MigrationScope scope) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT count(*) FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relkind = 'r' AND pg_get_userbyid(c.relowner) = ?
                  AND NOT has_table_privilege(c.oid, 'DELETE,TRUNCATE,TRIGGER,REFERENCES')
                  AND has_table_privilege(c.oid, 'INSERT')
                  AND (
                    (c.relname IN ('procesos', 'responsables_negocio') AND has_table_privilege(c.oid, 'SELECT')
                     AND has_table_privilege(c.oid, 'UPDATE'))
                    OR (c.relname = 'activos' AND has_table_privilege(c.oid, 'SELECT') AND NOT EXISTS (
                        SELECT 1 FROM pg_catalog.pg_attribute a
                        WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
                          AND has_column_privilege(c.oid, a.attnum, 'UPDATE') <>
                              (a.attname NOT IN ('id', 'empresa_id', 'codigo', 'codigo_normalizado'))
                    ))
                    OR (c.relname = 'eventos_actividad'
                        AND NOT has_any_column_privilege(c.oid, 'SELECT,UPDATE,REFERENCES'))
                  )
                """)) {
            statement.setString(1, scope.schema());
            statement.setString(2, scope.migrationRole());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) != 4) {
                    throw new IllegalStateException("Permisos del catálogo empresarial incompatibles.");
                }
            }
        }
        try (var statement = connection.prepareStatement("SELECT has_function_privilege(?, 'EXECUTE')")) {
            statement.setString(1, scope.schema() + ".historial_activo(uuid,uuid,integer,bigint)");
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !rows.getBoolean(1)) {
                    throw new IllegalStateException("Permisos de historial empresarial incompatibles.");
                }
            }
        }
    }
}
