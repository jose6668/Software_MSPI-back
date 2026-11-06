package software.mspi.empresas.internal.tenancy;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.hibernate.service.UnknownUnwrapTypeException;

public final class TenantConnectionProvider
        implements MultiTenantConnectionProvider<String>, CurrentTenantIdentifierResolver<String> {
    private final DataSource common;
    private final TenantContext context;
    TenantConnectionProvider(DataSource common, TenantContext context) { this.common = common; this.context = context; }
    @Override public Connection getAnyConnection() throws SQLException { return common.getConnection(); }
    @Override public void releaseAnyConnection(Connection connection) throws SQLException { connection.close(); }
    @Override public String resolveCurrentTenantIdentifier() { return context.require().company.toString(); }
    @Override public boolean validateExistingCurrentSessions() { return true; }
    @Override public boolean supportsAggressiveRelease() { return false; }
    @Override public boolean handlesConnectionSchema() { return true; }
    @Override public Connection getConnection(String tenantId) throws SQLException {
        var frame = context.require();
        if (!frame.company.toString().equals(tenantId)) frame.rejectChange();
        return frame.lease.borrow();
    }
    @Override public void releaseConnection(String tenantId, Connection connection) throws SQLException {
        var frame = context.require();
        if (!frame.company.toString().equals(tenantId)) {
            connection.close();
            frame.rejectChange();
        }
        frame.lease.release(connection);
    }
    @Override public boolean isUnwrappableAs(Class<?> type) { return type.isInstance(this); }
    @Override public <T> T unwrap(Class<T> type) {
        if (!type.isInstance(this)) throw new UnknownUnwrapTypeException(type);
        return type.cast(this);
    }
}
