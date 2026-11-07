package software.mspi.empresas.internal.migration;

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
        }
    }
}
