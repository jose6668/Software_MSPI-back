package software.mspi.empresas.internal.tenancy;
import java.util.UUID;
@FunctionalInterface
public interface TenantSecretSource {
    TenantConnectionSecret load(UUID companyId);
}
