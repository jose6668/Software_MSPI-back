package software.mspi.empresas.internal.migration;

import java.sql.Connection;
import java.sql.SQLException;

/** A database-wide lock shared by the technical tenant commands and the psql bootstrap. */
final class TenantMigrationLock {

    private TenantMigrationLock() {}

    static boolean acquire(Connection session) throws SQLException {
        try (var statement = session.createStatement();
                var result = statement.executeQuery("SELECT pg_catalog.pg_try_advisory_lock(1297305673, 5)")) {
            result.next();
            return result.getBoolean(1);
        }
    }
}
