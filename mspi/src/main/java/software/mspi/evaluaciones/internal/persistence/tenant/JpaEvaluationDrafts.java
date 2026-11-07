package software.mspi.evaluaciones.internal.persistence.tenant;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import software.mspi.catalogos.api.ReferenceCatalogs;
import software.mspi.empresas.api.CompanyAccess;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;
import software.mspi.evaluaciones.api.EvaluationDrafts;
import software.mspi.evaluaciones.api.EvaluationFailure;
import software.mspi.evaluaciones.api.EvaluationLifecycle;
import tools.jackson.databind.json.JsonMapper;

@Service
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
final class JpaEvaluationDrafts implements EvaluationDrafts, EvaluationLifecycle {

    private final TenantUnitOfWork work;
    private final ActivityLog audit;
    private final ReferenceCatalogs catalogs;
    private final CompanyAccess access;
    private final JsonMapper mapper;

    @PersistenceContext(unitName = "enterprise")
    private EntityManager entities;

    JpaEvaluationDrafts(TenantUnitOfWork work, ActivityLog audit, ReferenceCatalogs catalogs, CompanyAccess access,
                        JsonMapper mapper) {
        this.work = work;
        this.audit = audit;
        this.catalogs = catalogs;
        this.access = access;
        this.mapper = mapper;
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
            return "CERRADA".equals(evaluation.state) ? snapshot(evaluation).resultados()
                    : calculate(evaluation, evaluation.revision);
        });
    }

    private Results calculate(EvaluationRecord evaluation, long revision) {
        var rows = entities.createQuery("""
                select r.type, r.groupCode, r.applicability, r.score from EvaluationResponse r
                where r.company = :company and r.evaluation = :evaluation
                """, Object[].class).setParameter("company", evaluation.company).setParameter("evaluation", evaluation.id)
                .getResultList();
        return EvaluationResults.calculate(evaluation.id, revision, rows);
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

    @Override
    public void requireReadableResponse(Scope scope, UUID evaluation, UUID response) {
        read(scope, () -> findResponse(find(scope, evaluation, LockModeType.NONE), response));
    }

    @Override
    public <T> Optional<T> attachEvidence(Scope scope, UUID evaluationId, UUID responseId, long revision,
                                         Supplier<Optional<T>> publish) {
        return write(scope, () -> {
            var evaluation = editable(scope, evaluationId, revision);
            findResponse(evaluation, responseId);
            var published = publish.get();
            published.ifPresent(evidence -> evaluation.touch(Instant.now()));
            return published;
        });
    }

    @Override
    public Evaluation submit(Scope scope, UUID evaluation, long revision, Map<UUID, UUID> selected,
                             EvidenceVerifier verifier) {
        return reviewTransition(scope, evaluation, revision, selected, verifier, false);
    }

    @Override
    public Evaluation close(Scope scope, UUID evaluation, long revision, Map<UUID, UUID> reviewed,
                            EvidenceVerifier verifier) {
        return reviewTransition(scope, evaluation, revision, reviewed, verifier, true);
    }

    private Evaluation reviewTransition(Scope scope, UUID id, long revision, Map<UUID, UUID> selected,
                                        EvidenceVerifier verifier, boolean closing) {
        return write(scope, () -> {
            var evaluation = find(scope, id, LockModeType.PESSIMISTIC_WRITE);
            evaluation.requireState(closing ? "EN_REVISION" : "BORRADOR", revision);
            requireClosurePolicy(evaluation);
            var responses = entities.createQuery("""
                    select r from EvaluationResponse r where r.company = :company and r.evaluation = :evaluation
                    order by r.type, r.groupCode, r.code, r.id
                    """, EvaluationResponse.class).setParameter("company", scope.company()).setParameter("evaluation", id)
                    .getResultList();
            if (responses.isEmpty()) throw new EvaluationFailure(EvaluationFailure.Reason.POLICY);
            Map<UUID, EvidenceRef> previous = evaluation.previousEvaluation == null ? Map.of()
                    : snapshot(find(scope, evaluation.previousEvaluation, LockModeType.NONE)).evidencias();
            var proofs = EvaluationClosing.validate(scope, evaluation, responses, selected, previous, verifier);
            var detail = new Snapshot(EvaluationClosing.POLICY, closing ? calculate(evaluation, revision + 1) : null, proofs);
            access.require(scope.company(), CompanyPermission.EDIT_EVALUATION_DRAFT);
            return transition(scope, evaluation, closing ? "CERRADA" : "EN_REVISION",
                    closing ? "CERRAR" : "ENVIAR_REVISION", null, detail);
        });
    }

    @Override
    public Evaluation returnToDraft(Scope scope, UUID id, long revision, String reason) {
        return write(scope, () -> {
            var evaluation = find(scope, id, LockModeType.PESSIMISTIC_WRITE);
            evaluation.requireState("EN_REVISION", revision);
            return transition(scope, evaluation, "BORRADOR", "DEVOLVER", reason, null);
        });
    }

    @Override
    public Closure closure(Scope scope, UUID id) {
        return read(scope, () -> {
            var evaluation = find(scope, id, LockModeType.NONE);
            if (!"CERRADA".equals(evaluation.state)) throw new EvaluationFailure(EvaluationFailure.Reason.MISSING);
            return new Closure(id, evaluation.catalogVersion, evaluation.methodologyVersion, evaluation.closedBy,
                    evaluation.closedAt, snapshot(evaluation));
        });
    }

    @Override
    public Evaluation correct(Scope scope, UUID id, long revision, UUID responsible, String reason) {
        return write(scope, () -> {
            var original = find(scope, id, LockModeType.PESSIMISTIC_WRITE);
            original.requireState("CERRADA", revision);
            UUID correction = UUID.randomUUID();
            int changed = (Integer) entities.createNativeQuery("""
                    select corregir_evaluacion(:company, :original, :revision, :id, :responsible, :reason, :actor)
                    """, Integer.class).setParameter("company", scope.company()).setParameter("original", id)
                    .setParameter("revision", revision).setParameter("id", correction).setParameter("responsible", responsible)
                    .setParameter("reason", reason).setParameter("actor", scope.actor()).getSingleResult();
            requireOneTransition(changed);
            record(scope, correction, "EVALUACION", "CREAR_CORRECCION", null, "BORRADOR", reason);
            return find(scope, correction, LockModeType.NONE).view();
        });
    }

    @Override
    public Response confirmInherited(Scope scope, UUID evaluationId, UUID responseId, long revision) {
        return write(scope, () -> {
            var evaluation = editable(scope, evaluationId, revision);
            var response = findResponse(evaluation, responseId);
            if (response.previousResponse == null || response.inheritedConfirmed) {
                throw new EvaluationFailure(EvaluationFailure.Reason.NOT_INHERITED);
            }
            Instant now = Instant.now();
            response.confirm(scope.actor(), now);
            evaluation.touch(now);
            record(scope, response.id, "RESPUESTA_EVALUACION", "CONFIRMAR_HEREDADA", evaluation.state, evaluation.state);
            return response.view(evaluation.revision);
        });
    }

    private void requireClosurePolicy(EvaluationRecord evaluation) {
        var catalog = catalogs.catalog(evaluation.company, evaluation.catalogVersion);
        var method = catalogs.methodology(evaluation.company, evaluation.methodologyVersion);
        if (!"MSPI_DIAGNOSTICO_F1".equals(catalog.codigo()) || !"F1-1".equals(catalog.version())
                || !"EVALUACION_CONTROLES_V1".equals(method.identificador()) || method.aprobadaEn() == null) {
            throw new EvaluationFailure(EvaluationFailure.Reason.POLICY);
        }
    }

    private Snapshot snapshot(EvaluationRecord evaluation) {
        String value = (String) entities.createNativeQuery("""
                select cast(instantanea_cierre as text) from evaluaciones where empresa_id = :company and id = :id
                """, String.class).setParameter("company", evaluation.company).setParameter("id", evaluation.id)
                .getSingleResult();
        return mapper.readValue(value, Snapshot.class);
    }

    private Evaluation transition(Scope scope, EvaluationRecord evaluation, String state, String action, String reason,
                                  Snapshot snapshot) {
        String previous = evaluation.state;
        int changed = (Integer) entities.createNativeQuery("""
                select transicionar_evaluacion(:company, :id, :revision, :state, :actor, cast(:detail as jsonb))
                """, Integer.class).setParameter("company", scope.company()).setParameter("id", evaluation.id)
                .setParameter("revision", evaluation.revision).setParameter("state", state)
                .setParameter("actor", scope.actor()).setParameter("detail", snapshot == null ? null : mapper.writeValueAsString(snapshot))
                .getSingleResult();
        requireOneTransition(changed);
        record(scope, evaluation.id, "EVALUACION", action, previous, state, reason);
        entities.refresh(evaluation);
        return evaluation.view();
    }

    private static void requireOneTransition(int changed) {
        if (changed != 1) throw new EvaluationFailure(EvaluationFailure.Reason.STATE);
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
        record(scope, object, type, action, previous, next, null);
    }

    private void record(Scope scope, UUID object, String type, String action, String previous, String next, String reason) {
        entities.flush();
        audit.append(new ActivityLog.Event(scope.company(), scope.actor(), action, type, object, previous, next,
                reason, scope.correlation()));
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
                if ("correccion_sucesora_unica".equals(name)) {
                    throw new EvaluationFailure(EvaluationFailure.Reason.CORRECTION_EXISTS);
                }
            }
            throw failure;
        }
    }
}
