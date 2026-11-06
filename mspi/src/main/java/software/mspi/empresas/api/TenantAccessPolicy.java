package software.mspi.empresas.api;
import java.util.UUID;
/** Security integration point; must validate current identity and company access. */
@FunctionalInterface
public interface TenantAccessPolicy {
    void requireAccess(UUID actorId, UUID companyId);
}
