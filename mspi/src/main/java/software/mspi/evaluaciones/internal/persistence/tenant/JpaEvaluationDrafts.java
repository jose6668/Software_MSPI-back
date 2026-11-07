package software.mspi.evaluaciones.internal.persistence.tenant;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.hibernate.JDBCException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import software.mspi.auditoria.api.ActivityLog;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;
import software.mspi.evaluaciones.api.EvaluationDrafts;
import software.mspi.evaluaciones.api.EvaluationFailure;

@Service
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
final class JpaEvaluationDrafts implements EvaluationDrafts {

    private final TenantUnitOfWork work;
    private final ActivityLog audit;

    @PersistenceContext(unitName = "enterprise")
    private EntityManager entities;

    JpaEvaluationDrafts(TenantUnitOfWork work, ActivityLog audit) {
        this.work = work;
        this.audit = audit;
    }

    @Override
    public Page<Evaluation> list(Scope scope, int page, int size) {
        return read(scope, () -> page(entities.createQuery("""
                select e from EvaluationRecord e where e.company = :company order by e.date desc, e.id
                """, EvaluationRecord.class).setParameter("company", scope.company()), page, size, EvaluationRecord::view));
    }

    @Override
    public Evaluation get(Scope scope, UUID evaluation) {
        return read(scope, () -> find(scope, evaluation, LockModeType.NONE).view());
    }

    @Override
    public Evaluation create(Scope scope, Create input) {
        return write(scope, () -> {
            var evaluation = new EvaluationRecord(scope, input, Instant.now());
            entities.persist(evaluation);
            record(scope, evaluation.id, "EVALUACION", "CREAR", null, evaluation.state);
            return evaluation.view();
        });
    }

    @Override
    public Page<Response> responses(Scope scope, UUID evaluationId, ElementType type, String group, int page, int size) {
        return read(scope, () -> {
            var evaluation = find(scope, evaluationId, LockModeType.PESSIMISTIC_READ);
            String query = """
                    select r from EvaluationResponse r where r.company = :company and r.evaluation = :evaluation
                    """;
            if (type != null) query += " and r.type = :type";
            if (group != null) query += " and r.groupCode = :group";
            var selection = entities.createQuery(query + " order by r.type, r.groupCode, r.code, r.id", EvaluationResponse.class)
                    .setParameter("company", scope.company()).setParameter("evaluation", evaluationId);
            if (type != null) selection.setParameter("type", type);
            if (group != null) selection.setParameter("group", group);
            return page(selection, page, size, item -> item.view(evaluation.revision));
        });
    }

    @Override
    public Response updateResponse(Scope scope, UUID evaluationId, UUID responseId, long revision, Answer input) {
        return write(scope, () -> {
            var evaluation = editable(scope, evaluationId, revision);
            var response = findResponse(evaluation, responseId);
            Instant now = Instant.now();
            response.update(input, scope.actor(), now);
            evaluation.touch(now);
            record(scope, response.id, "RESPUESTA_EVALUACION", "EDITAR", evaluation.state, evaluation.state);
            return response.view(evaluation.revision);
        });
    }

    @Override
    public Results results(Scope scope, UUID evaluationId) {
        return read(scope, () -> {
            var evaluation = find(scope, evaluationId, LockModeType.PESSIMISTIC_READ);
            var rows = entities.createQuery("""
                    select r.type, r.groupCode, r.applicability, r.score from EvaluationResponse r
                    where r.company = :company and r.evaluation = :evaluation
                    """, Object[].class).setParameter("company", scope.company()).setParameter("evaluation", evaluationId)
                    .getResultList();
            return EvaluationResults.calculate(evaluation, rows);
        });
    }

    @Override
    public Page<Finding> findings(Scope scope, UUID evaluationId, UUID responseId, int page, int size) {
        return read(scope, () -> {
            var evaluation = find(scope, evaluationId, LockModeType.PESSIMISTIC_READ);
            findResponse(evaluation, responseId);
            var query = entities.createQuery("""
                    select f from EvaluationFinding f
                    where f.company = :company and f.evaluation = :evaluation and f.response = :response
                    order by f.createdAt, f.id
                    """, EvaluationFinding.class).setParameter("company", scope.company())
                    .setParameter("evaluation", evaluationId).setParameter("response", responseId);
            return page(query, page, size, item -> item.view(evaluation.revision));
        });
    }

    @Override
    public Finding createFinding(Scope scope, UUID evaluationId, UUID responseId, long revision, Gap input) {
        return write(scope, () -> {
            var evaluation = editable(scope, evaluationId, revision);
            findResponse(evaluation, responseId);
            Instant now = Instant.now();
            var finding = new EvaluationFinding(scope, evaluationId, responseId, now);
            finding.update(input, scope.actor(), now);
            entities.persist(finding);
            evaluation.touch(now);
            record(scope, finding.id, "HALLAZGO_EVALUACION", "CREAR", null, evaluation.state);
            return finding.view(evaluation.revision);
        });
    }

    @Override
    public Finding updateFinding(Scope scope, UUID evaluationId, UUID responseId, UUID findingId, long revision, Gap input) {
        return write(scope, () -> {
            var evaluation = editable(scope, evaluationId, revision);
            var finding = entities.find(EvaluationFinding.class, findingId);
            if (finding == null || !scope.company().equals(finding.company) || !evaluationId.equals(finding.evaluation)
                    || !responseId.equals(finding.response)) {
                throw new EvaluationFailure(EvaluationFailure.Reason.MISSING);
            }
            Instant now = Instant.now();
            finding.update(input, scope.actor(), now);
            evaluation.touch(now);
            record(scope, finding.id, "HALLAZGO_EVALUACION", "EDITAR", evaluation.state, evaluation.state);
            return finding.view(evaluation.revision);
        });
    }

    private EvaluationRecord editable(Scope scope, UUID evaluation, long revision) {
        var record = find(scope, evaluation, LockModeType.PESSIMISTIC_WRITE);
        record.requireEditable(revision);
        return record;
    }

    private EvaluationRecord find(Scope scope, UUID evaluation, LockModeType lock) {
        var record = entities.find(EvaluationRecord.class, evaluation, lock);
        if (record == null || !scope.company().equals(record.company)) {
            throw new EvaluationFailure(EvaluationFailure.Reason.MISSING);
        }
        return record;
    }

    private EvaluationResponse findResponse(EvaluationRecord evaluation, UUID id) {
        var response = entities.find(EvaluationResponse.class, id);
        if (response == null || !evaluation.company.equals(response.company) || !evaluation.id.equals(response.evaluation)) {
            throw new EvaluationFailure(EvaluationFailure.Reason.MISSING);
        }
        return response;
    }

    private static <T, R> Page<R> page(TypedQuery<T> query, int page, int size, Function<T, R> view) {
        long offset = (long) page * size;
        if (offset > Integer.MAX_VALUE) {
            return new Page<>(List.of(), page, size, false);
        }
        var rows = query.setFirstResult((int) offset).setMaxResults(size + 1).getResultList();
        return new Page<>(rows.stream().limit(size).map(view).toList(), page, size, rows.size() > size);
    }

    private void record(Scope scope, UUID object, String type, String action, String previous, String next) {
        entities.flush();
        audit.append(new ActivityLog.Event(scope.company(), scope.actor(), action, type, object, previous, next,
                null, scope.correlation()));
    }

    private <T> T read(Scope scope, Supplier<T> operation) {
        return work.execute(scope.actor(), scope.company(), CompanyPermission.READ_EVALUATIONS, operation);
    }

    private <T> T write(Scope scope, Supplier<T> operation) {
        try {
            return work.execute(scope.actor(), scope.company(), CompanyPermission.EDIT_EVALUATION_DRAFT, operation);
        } catch (OptimisticLockException conflict) {
            throw new OptimisticLockingFailureException("Revisión desactualizada.", conflict);
        } catch (JDBCException failure) {
            if ("P1701".equals(failure.getSQLState())) throw new EvaluationFailure(EvaluationFailure.Reason.RESPONSIBLE);
            if ("P1702".equals(failure.getSQLState())) throw new EvaluationFailure(EvaluationFailure.Reason.VERSION);
            if (failure instanceof ConstraintViolationException constraint) {
                String name = constraint.getConstraintName();
                if ("evaluacion_responsable_local".equals(name)) {
                    throw new EvaluationFailure(EvaluationFailure.Reason.RESPONSIBLE);
                }
                if ("evaluacion_catalogo".equals(name) || "evaluacion_metodologia".equals(name)) {
                    throw new EvaluationFailure(EvaluationFailure.Reason.VERSION);
                }
            }
            throw failure;
        }
    }
}
