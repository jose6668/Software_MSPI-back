package software.mspi.empresas.api;
import java.util.UUID;
import java.util.function.Supplier;
/** Runs an authorized operation with one company and transaction fixed until completion. */
public interface TenantUnitOfWork {
    <T> T execute(UUID actorId, UUID companyId, Supplier<T> operation);
}
