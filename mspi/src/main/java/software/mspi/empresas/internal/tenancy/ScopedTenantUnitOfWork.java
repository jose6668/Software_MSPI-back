package software.mspi.empresas.internal.tenancy;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.mspi.empresas.api.*;
import software.mspi.empresas.internal.migration.MigrationScope;
import software.mspi.empresas.internal.migration.SchemaMigrator;
import software.mspi.empresas.internal.persistence.common.CompanyRegistryRepository;

final class ScopedTenantUnitOfWork implements TenantUnitOfWork {
    private final TenantAccessPolicy policy;
    private final CompanyRegistryRepository registry;
    private final TenantConnectionPools pools;
    private final TenantContext context;
    private final TransactionTemplate transactions;

    ScopedTenantUnitOfWork(TenantAccessPolicy policy, CompanyRegistryRepository registry,
            TenantConnectionPools pools, TenantContext context, TransactionTemplate transactions) {
        this.policy = policy; this.registry = registry; this.pools = pools;
        this.context = context; this.transactions = transactions;
    }

    @Override public <T> T execute(UUID actor, UUID company, CompanyPermission permission, Supplier<T> operation) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(company, "company");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(operation, "operation");
        var existing = context.current();
        if (existing != null) {
            if (!existing.actor.equals(actor) || !existing.company.equals(company) || existing.permission != permission) {
                existing.rejectChange();
            }
            return operation.get();
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Inicie la unidad empresarial fuera de otras transacciones.");
        }
        policy.requireAccess(actor, company, permission);
        var entry = registry.findById(company)
                .orElseThrow(() -> new AccessDeniedException("Empresa no operable."));
        if (!"ACTIVA".equals(entry.getState())
                || !SchemaMigrator.TENANT_STRUCTURE_VERSION.equals(entry.getStructureVersion())
                || !MigrationScope.tenant(company).schema().equals(entry.getSchemaName())) {
            throw new AccessDeniedException("Empresa no operable.");
        }

        try (var lease = pools.reserve(company)) {
            var frame = new TenantContext.Frame(actor, company, permission, lease);
            context.bind(frame);
            try {
                return transactions.execute(status -> {
                    frame.status = status;
                    T result = operation.get();
                    if (status.isRollbackOnly()) throw new IllegalStateException("La unidad empresarial debe revertirse.");
                    return result;
                });
            } finally {
                context.clear();
            }
        }
    }
}
