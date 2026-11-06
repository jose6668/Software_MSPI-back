package software.mspi.empresas.internal.tenancy;
import java.util.UUID;
import org.springframework.core.env.Environment;

public final class EnvironmentTenantSecretSource implements TenantSecretSource {
    private final Environment environment;
    public EnvironmentTenantSecretSource(Environment environment) { this.environment = environment; }
    @Override public TenantConnectionSecret load(UUID companyId) {
        String prefix = "mspi.tenancy.connections." + companyId.toString().replace("-", "") + ".";
        return new TenantConnectionSecret(environment.getProperty(prefix + "jdbc-url"),
                environment.getProperty(prefix + "username"), environment.getProperty(prefix + "password"));
    }
}
