package software.mspi.activos.internal.persistence.tenant;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import software.mspi.activos.api.CatalogFailure;
import software.mspi.activos.api.ProcessCatalog;
import software.mspi.auditoria.api.ActivityLog;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;

@Service
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
final class JpaProcessCatalog implements ProcessCatalog {

    private final TenantUnitOfWork work;
    private final ActivityLog audit;

    @PersistenceContext(unitName = "enterprise")
    private EntityManager entities;

    JpaProcessCatalog(TenantUnitOfWork work, ActivityLog audit) {
        this.work = work;
        this.audit = audit;
    }

    @Override
    public Page<Process> processes(Scope scope, State state, UUID responsibleId, int page, int size) {
        return read(scope, () -> list(BusinessProcess.class, scope, state, responsibleId, page, size, BusinessProcess::view));
    }

    @Override
    public Process process(Scope scope, UUID id) {
        return read(scope, () -> find(BusinessProcess.class, scope, id, LockModeType.NONE).view());
    }

    @Override
    public Process createProcess(Scope scope, ProcessInput input) {
        return write(scope, () -> {
            var process = new BusinessProcess(scope.company());
            validateResponsible(scope, process, input);
            process.update(input);
            entities.persist(process);
            record(scope, process, "CREAR", "PROCESO", null, null);
            return process.view();
        });
    }

    @Override
    public Process updateProcess(Scope scope, UUID id, long revision, ProcessInput input) {
        return write(scope, () -> {
            var process = find(BusinessProcess.class, scope, id, LockModeType.PESSIMISTIC_WRITE);
            process.requireEditable(revision);
            validateResponsible(scope, process, input);
            process.update(input);
            record(scope, process, "EDITAR", "PROCESO", State.ACTIVO, null);
            return process.view();
        });
    }

    @Override
    public Process archiveProcess(Scope scope, UUID id, long revision, String reason) {
        return write(scope, () -> archive(BusinessProcess.class, scope, id, revision, "PROCESO", reason).view());
    }

    @Override
    public Page<Responsible> responsibles(Scope scope, State state, int page, int size) {
        return read(scope, () -> list(BusinessResponsible.class, scope, state, null, page, size, BusinessResponsible::view));
    }

    @Override
    public Responsible responsible(Scope scope, UUID id) {
        return read(scope, () -> find(BusinessResponsible.class, scope, id, LockModeType.NONE).view());
    }

    @Override
    public Responsible createResponsible(Scope scope, ResponsibleInput input) {
        return write(scope, () -> {
            var responsible = new BusinessResponsible(scope.company());
            responsible.update(input);
            entities.persist(responsible);
            record(scope, responsible, "CREAR", "RESPONSABLE_NEGOCIO", null, null);
            return responsible.view();
        });
    }

    @Override
    public Responsible updateResponsible(Scope scope, UUID id, long revision, ResponsibleInput input) {
        return write(scope, () -> {
            var responsible = find(BusinessResponsible.class, scope, id, LockModeType.PESSIMISTIC_WRITE);
            responsible.requireEditable(revision);
            responsible.update(input);
            record(scope, responsible, "EDITAR", "RESPONSABLE_NEGOCIO", State.ACTIVO, null);
            return responsible.view();
        });
    }

    @Override
    public Responsible archiveResponsible(Scope scope, UUID id, long revision, String reason) {
        return write(scope, () -> archive(BusinessResponsible.class, scope, id, revision, "RESPONSABLE_NEGOCIO", reason).view());
    }

    private <T extends CatalogRecord> T archive(Class<T> type, Scope scope, UUID id, long revision,
                                                  String objectType, String reason) {
        var entry = find(type, scope, id, LockModeType.PESSIMISTIC_WRITE);
        entry.requireEditable(revision);
        entry.state = State.ARCHIVADO;
        record(scope, entry, "ARCHIVAR", objectType, State.ACTIVO, reason);
        return entry;
    }

    private void validateResponsible(Scope scope, BusinessProcess process, ProcessInput input) {
        UUID id = input.responsableId();
        if (id == null || Objects.equals(id, process.responsibleId)) {
            return;
        }
        var responsible = entities.find(BusinessResponsible.class, id, LockModeType.PESSIMISTIC_READ);
        if (responsible == null || !scope.company().equals(responsible.companyId) || responsible.state != State.ACTIVO) {
            throw new CatalogFailure(CatalogFailure.Reason.REFERENCE);
        }
    }

    private <T extends CatalogRecord> T find(Class<T> type, Scope scope, UUID id, LockModeType lock) {
        T entry = entities.find(type, id, lock);
        if (entry == null || !scope.company().equals(entry.companyId)) {
            throw new CatalogFailure(CatalogFailure.Reason.MISSING);
        }
        return entry;
    }

    private <T extends CatalogRecord, R> Page<R> list(Class<T> type, Scope scope, State state, UUID responsibleId,
                                                       int page, int size, Function<T, R> view) {
        String jpql = "select e from " + type.getSimpleName() + " e where e.companyId = :company";
        if (state != null) {
            jpql += " and e.state = :state";
        }
        if (responsibleId != null) {
            jpql += " and e.responsibleId = :responsible";
        }
        var query = entities.createQuery(jpql + " order by e.name, e.id", type).setParameter("company", scope.company());
        if (state != null) {
            query.setParameter("state", state);
        }
        if (responsibleId != null) {
            query.setParameter("responsible", responsibleId);
        }
        long offset = (long) page * size;
        if (offset > Integer.MAX_VALUE) {
            return new Page<>(List.of(), page, size, false);
        }
        List<T> rows = query.setFirstResult((int) offset).setMaxResults(size + 1).getResultList();
        return new Page<>(rows.stream().limit(size).map(view).toList(), page, size, rows.size() > size);
    }

    private void record(Scope scope, CatalogRecord entry, String action, String objectType, State previous, String reason) {
        entities.flush();
        audit.append(new ActivityLog.Event(scope.company(), scope.actor(), action, objectType, entry.id,
                previous == null ? null : previous.name(), entry.state.name(), reason, scope.correlation()));
    }

    private <T> T read(Scope scope, Supplier<T> operation) {
        return work.execute(scope.actor(), scope.company(), CompanyPermission.READ_PROCESS_CATALOG, operation);
    }

    private <T> T write(Scope scope, Supplier<T> operation) {
        try {
            return work.execute(scope.actor(), scope.company(), CompanyPermission.EDIT_PROCESS_CATALOG, operation);
        } catch (OptimisticLockException conflict) {
            throw new OptimisticLockingFailureException("Revisión desactualizada.", conflict);
        } catch (ConstraintViolationException violation) {
            if ("responsable_membresia_local".equals(violation.getConstraintName())) {
                throw new CatalogFailure(CatalogFailure.Reason.REFERENCE);
            }
            throw violation;
        }
    }
}
