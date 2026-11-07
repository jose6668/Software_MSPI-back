package software.mspi.activos.internal.persistence.tenant;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import software.mspi.activos.api.AssetClassification;
import software.mspi.activos.api.CatalogFailure;
import software.mspi.activos.api.ClassificationFailure;
import software.mspi.activos.api.ProcessCatalog.Page;
import software.mspi.activos.api.ProcessCatalog.Scope;
import software.mspi.activos.api.ProcessCatalog.State;
import software.mspi.auditoria.api.ActivityLog;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;

@Service
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
final class JpaAssetClassification implements AssetClassification {

    private final TenantUnitOfWork work;
    private final ActivityLog audit;

    @PersistenceContext(unitName = "enterprise")
    private EntityManager entities;

    JpaAssetClassification(TenantUnitOfWork work, ActivityLog audit) {
        this.work = work;
        this.audit = audit;
    }

    @Override
    public Page<Valuation> list(Scope scope, UUID assetId, int page, int size) {
        return work.execute(scope.actor(), scope.company(), CompanyPermission.READ_ASSETS, () -> {
            var asset = findAsset(scope, assetId, LockModeType.PESSIMISTIC_READ);
            long offset = (long) page * size;
            if (offset > Integer.MAX_VALUE) {
                return new Page<>(List.of(), page, size, false);
            }
            var rows = entities.createQuery("""
                    select v from AssetValuation v where v.companyId = :company and v.assetId = :asset
                    order by v.number desc
                    """, AssetValuation.class).setParameter("company", scope.company()).setParameter("asset", assetId)
                    .setFirstResult((int) offset).setMaxResults(size + 1).getResultList();
            return new Page<>(rows.stream().limit(size).map(v -> v.view(asset.currentValuation)).toList(),
                    page, size, rows.size() > size);
        });
    }

    @Override
    public Valuation get(Scope scope, UUID assetId, UUID valuationId) {
        return work.execute(scope.actor(), scope.company(), CompanyPermission.READ_ASSETS, () -> {
            var asset = findAsset(scope, assetId, LockModeType.PESSIMISTIC_READ);
            return findValuation(asset, valuationId).view(asset.currentValuation);
        });
    }

    @Override
    public Valuation start(Scope scope, UUID assetId, Input input, String reason) {
        return mutate(scope, () -> {
            var asset = editableAsset(scope, assetId);
            boolean pending = !entities.createQuery("""
                    select v.id from AssetValuation v
                    where v.companyId = :company and v.assetId = :asset and v.confirmedAt is null
                    """, UUID.class).setParameter("company", scope.company()).setParameter("asset", assetId)
                    .setMaxResults(1).getResultList().isEmpty();
            if (pending) {
                throw new ClassificationFailure(ClassificationFailure.Reason.DRAFT_EXISTS, List.of());
            }
            int number = 1;
            if (asset.currentValuation != null) {
                if (reason == null) {
                    throw new ClassificationFailure(ClassificationFailure.Reason.INVALID, List.of("motivoRevision"));
                }
                number = findValuation(asset, asset.currentValuation).number + 1;
            }
            Instant now = Instant.now();
            var valuation = new AssetValuation(scope.company(), assetId, number, scope.actor(), now, reason);
            valuation.update(ClassificationRules.validate(input), scope.actor(), now);
            entities.persist(valuation);
            record(scope, valuation, "INICIAR_CLASIFICACION", null, Status.PENDIENTE, reason);
            return valuation.view(asset.currentValuation);
        });
    }

    @Override
    public Valuation edit(Scope scope, UUID assetId, UUID valuationId, long revision, Input input) {
        return mutate(scope, () -> {
            var asset = editableAsset(scope, assetId);
            var valuation = findValuation(asset, valuationId);
            valuation.requireDraft(revision);
            valuation.update(ClassificationRules.validate(input), scope.actor(), Instant.now());
            record(scope, valuation, "EDITAR_CLASIFICACION", Status.PENDIENTE, Status.PENDIENTE, null);
            return valuation.view(asset.currentValuation);
        });
    }

    @Override
    public Valuation confirm(Scope scope, UUID assetId, UUID valuationId, long revision) {
        return mutate(scope, () -> {
            var asset = editableAsset(scope, assetId);
            var valuation = findValuation(asset, valuationId);
            valuation.requireDraft(revision);
            ClassificationRules.requireComplete(valuation.input());
            valuation.confirm(scope.actor(), Instant.now());
            entities.flush();
            asset.currentValuation = valuation.id;
            record(scope, valuation, "CONFIRMAR_CLASIFICACION", Status.PENDIENTE, Status.COMPLETO, null);
            return valuation.view(asset.currentValuation);
        });
    }

    private AssetRecord editableAsset(Scope scope, UUID id) {
        var asset = findAsset(scope, id, LockModeType.PESSIMISTIC_WRITE);
        if (asset.state != State.ACTIVO) {
            throw new CatalogFailure(CatalogFailure.Reason.ARCHIVED);
        }
        return asset;
    }

    private AssetRecord findAsset(Scope scope, UUID id, LockModeType lock) {
        var asset = entities.find(AssetRecord.class, id, lock);
        if (asset == null || !scope.company().equals(asset.companyId)) {
            throw new CatalogFailure(CatalogFailure.Reason.MISSING);
        }
        return asset;
    }

    private AssetValuation findValuation(AssetRecord asset, UUID id) {
        var valuation = entities.find(AssetValuation.class, id);
        if (valuation == null || !asset.id.equals(valuation.assetId) || !asset.companyId.equals(valuation.companyId)) {
            throw new CatalogFailure(CatalogFailure.Reason.MISSING);
        }
        return valuation;
    }

    private void record(Scope scope, AssetValuation valuation, String action, Status previous, Status state, String reason) {
        entities.flush();
        audit.append(new ActivityLog.Event(scope.company(), scope.actor(), action, "VALORACION_ACTIVO", valuation.id,
                previous == null ? null : previous.name(), state.name(), reason, scope.correlation()));
    }

    private Valuation mutate(Scope scope, Supplier<Valuation> operation) {
        try {
            return work.execute(scope.actor(), scope.company(), CompanyPermission.CLASSIFY_ASSETS, operation);
        } catch (OptimisticLockException conflict) {
            throw new OptimisticLockingFailureException("Revisión desactualizada.", conflict);
        }
    }
}
