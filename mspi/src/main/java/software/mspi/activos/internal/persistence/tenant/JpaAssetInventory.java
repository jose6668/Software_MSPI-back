package software.mspi.activos.internal.persistence.tenant;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import software.mspi.activos.api.AssetInventory;
import software.mspi.activos.api.CatalogFailure;
import software.mspi.activos.api.ProcessCatalog.Page;
import software.mspi.activos.api.ProcessCatalog.Scope;
import software.mspi.activos.api.ProcessCatalog.State;
import software.mspi.auditoria.api.ActivityLog;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;

@Service
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
final class JpaAssetInventory implements AssetInventory {

    private final TenantUnitOfWork work;
    private final ActivityLog audit;

    @PersistenceContext(unitName = "enterprise")
    private EntityManager entities;

    JpaAssetInventory(TenantUnitOfWork work, ActivityLog audit) {
        this.work = work;
        this.audit = audit;
    }

    @Override
    public Page<Asset> list(Scope scope, Filter filter, int page, int size) {
        return work.execute(scope.actor(), scope.company(), CompanyPermission.READ_ASSETS,
                () -> search(scope, filter, page, size));
    }

    @Override
    public Asset get(Scope scope, UUID id) {
        return work.execute(scope.actor(), scope.company(), CompanyPermission.READ_ASSETS,
                () -> find(scope, id, LockModeType.NONE).view());
    }

    @Override
    public Asset create(Scope scope, String code, Input input) {
        return mutate(scope, CompanyPermission.EDIT_ASSETS, () -> {
            validateReferences(scope, null, input.referencias());
            var asset = new AssetRecord(scope.company(), code);
            var changes = asset.update(input);
            changes.add("codigo");
            entities.persist(asset);
            record(scope, asset, "CREAR", null, null, changes, null);
            return asset.view();
        });
    }

    @Override
    public Asset edit(Scope scope, UUID id, long revision, Input input) {
        return mutate(scope, CompanyPermission.EDIT_ASSETS, () -> {
            var asset = find(scope, id, LockModeType.PESSIMISTIC_WRITE);
            asset.requireEditable(revision);
            References previous = asset.references();
            validateReferences(scope, previous, input.referencias());
            var changes = asset.update(input);
            if (!changes.isEmpty()) {
                record(scope, asset, "EDITAR", State.ACTIVO, null, changes, previous);
            }
            return asset.view();
        });
    }

    @Override
    public Asset archive(Scope scope, UUID id, long revision, String reason) {
        return mutate(scope, CompanyPermission.CHANGE_ASSET_STATE, () -> {
            var asset = find(scope, id, LockModeType.PESSIMISTIC_WRITE);
            asset.requireEditable(revision);
            asset.state = State.ARCHIVADO;
            record(scope, asset, "ARCHIVAR", State.ACTIVO, reason, List.of("estado"), asset.references());
            return asset.view();
        });
    }

    @Override
    public Asset restore(Scope scope, UUID id, long revision, String reason, References replacements) {
        return mutate(scope, CompanyPermission.CHANGE_ASSET_STATE, () -> {
            var asset = find(scope, id, LockModeType.PESSIMISTIC_WRITE);
            asset.requireRevision(revision);
            if (asset.state != State.ARCHIVADO) {
                throw new CatalogFailure(CatalogFailure.Reason.INCOMPATIBLE_STATE);
            }
            References previous = asset.references();
            References desired = replacements == null ? previous : replacements;
            validateReferences(scope, null, desired);
            var changes = asset.changeReferences(desired);
            asset.state = State.ACTIVO;
            changes.add("estado");
            record(scope, asset, "RESTAURAR", State.ARCHIVADO, reason, changes, previous);
            return asset.view();
        });
    }

    @Override
    public Page<ActivityLog.Entry> history(Scope scope, UUID id, int page, int size) {
        return work.execute(scope.actor(), scope.company(), CompanyPermission.READ_ASSET_HISTORY, () -> {
            find(scope, id, LockModeType.NONE);
            var rows = audit.assetHistory(scope.company(), id, size + 1, (long) page * size);
            return new Page<>(rows.stream().limit(size).toList(), page, size, rows.size() > size);
        });
    }

    private AssetRecord find(Scope scope, UUID id, LockModeType lock) {
        var asset = entities.find(AssetRecord.class, id, lock);
        if (asset == null || !scope.company().equals(asset.companyId)) {
            throw new CatalogFailure(CatalogFailure.Reason.MISSING);
        }
        return asset;
    }

    private void validateReferences(Scope scope, References current, References desired) {
        if (current == null || !Objects.equals(current.procesoId(), desired.procesoId())) {
            requireActive(BusinessProcess.class, scope, desired.procesoId());
        }
        if (current == null || !Objects.equals(current.propietarioId(), desired.propietarioId())) {
            requireActive(BusinessResponsible.class, scope, desired.propietarioId());
        }
        if (desired.custodioId() != null && (current == null || !Objects.equals(current.custodioId(), desired.custodioId()))) {
            requireActive(BusinessResponsible.class, scope, desired.custodioId());
        }
    }

    private void requireActive(Class<? extends CatalogRecord> type, Scope scope, UUID id) {
        var record = entities.find(type, id, LockModeType.PESSIMISTIC_READ);
        if (record == null || !scope.company().equals(record.companyId) || record.state != State.ACTIVO) {
            throw new CatalogFailure(CatalogFailure.Reason.REFERENCE);
        }
    }

    private Page<Asset> search(Scope scope, Filter filter, int page, int size) {
        long offset = (long) page * size;
        if (offset > Integer.MAX_VALUE) {
            return new Page<>(List.of(), page, size, false);
        }
        String jpql = "select e from AssetRecord e where e.companyId = :company";
        var params = new HashMap<String, Object>();
        params.put("company", scope.company());
        if (filter.estado() != null) {
            jpql += " and e.state = :state";
            params.put("state", filter.estado());
        }
        if (filter.tipo() != null) {
            jpql += " and e.type = :type";
            params.put("type", filter.tipo());
        }
        if (filter.procesoId() != null) {
            jpql += " and e.processId = :process";
            params.put("process", filter.procesoId());
        }
        if (filter.texto() != null && !filter.texto().isBlank()) {
            jpql += " and (e.codeKey like :code escape '!' or lower(e.name) like lower(:name) escape '!')";
            params.put("code", literalPattern(AssetRecord.normalizedCode(filter.texto())));
            params.put("name", literalPattern(filter.texto().strip()));
        }
        var query = entities.createQuery(jpql + " order by e.codeKey, e.id", AssetRecord.class);
        params.forEach(query::setParameter);
        var rows = query.setFirstResult((int) offset).setMaxResults(size + 1).getResultList();
        return new Page<>(rows.stream().limit(size).map(AssetRecord::view).toList(), page, size, rows.size() > size);
    }

    private static String literalPattern(String value) {
        return "%" + value.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private void record(Scope scope, AssetRecord asset, String action, State before, String reason, List<String> fields,
                        References previous) {
        entities.flush();
        References current = asset.references();
        var references = current.equals(previous) ? null
                : new ActivityLog.ReferenceChange(historyReferences(previous), historyReferences(current));
        audit.append(new ActivityLog.Event(scope.company(), scope.actor(), action, "ACTIVO", asset.id,
                before == null ? null : before.name(), asset.state.name(), reason, scope.correlation(), fields, references));
    }

    private static ActivityLog.AssetReferences historyReferences(References references) {
        return references == null ? null
                : new ActivityLog.AssetReferences(references.procesoId(), references.propietarioId(), references.custodioId());
    }

    private Asset mutate(Scope scope, CompanyPermission permission, Supplier<Asset> mutation) {
        try {
            return work.execute(scope.actor(), scope.company(), permission, mutation);
        } catch (OptimisticLockException conflict) {
            throw new OptimisticLockingFailureException("Revisión desactualizada.", conflict);
        } catch (ConstraintViolationException violation) {
            if ("activo_codigo_local".equals(violation.getConstraintName())) {
                throw new CatalogFailure(CatalogFailure.Reason.DUPLICATE_CODE);
            }
            throw violation;
        }
    }
}
