package software.mspi.acciones.internal.persistence.tenant;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import software.mspi.acciones.api.ImprovementActions;
import software.mspi.acciones.api.ActionFailure;
import software.mspi.acciones.api.ImprovementActions.Action;
import software.mspi.acciones.api.ImprovementActions.ContextEvidence;
import software.mspi.acciones.api.ImprovementActions.ContextEvidenceView;
import software.mspi.acciones.api.ImprovementActions.Draft;
import software.mspi.auditoria.api.ActivityLog;
import software.mspi.empresas.api.CompanyAccess;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;

@Service
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
final class JpaImprovementActions implements ImprovementActions {
    private final TenantUnitOfWork work;
    private final CompanyAccess access;
    private final ActivityLog audit;

    @PersistenceContext(unitName = "enterprise")
    private EntityManager entities;

    JpaImprovementActions(TenantUnitOfWork work, CompanyAccess access, ActivityLog audit) {
        this.work = work;
        this.access = access;
        this.audit = audit;
    }

    @Override
    public Page actions(Scope scope, int page, int size) {
        var membership = readableMembership(scope);
        boolean assignedOnly = membership != null;
        var permission = assignedOnly ? CompanyPermission.READ_ASSIGNED_ACTIONS : CompanyPermission.READ_ACTIONS;
        return work.execute(scope.actor(), scope.company(), permission, () -> {
            var query = entities.createQuery("""
                    select a from ImprovementActionRecord a
                    where a.company = :company
                    """ + (assignedOnly ? "and a.assignee = :assignee " : "")
                    + "order by a.created desc, a.id", ImprovementActionRecord.class)
                    .setParameter("company", scope.company());
            if (assignedOnly) query.setParameter("assignee", membership);
            var rows = query.setFirstResult(Math.multiplyExact(page, size)).setMaxResults(size + 1).getResultList();
            boolean more = rows.size() > size;
            var elements = rows.stream().limit(size).map(this::view).toList();
            return new Page(elements, page, size, more);
        });
    }

    @Override
    public Action get(Scope scope, UUID id) {
        var membership = readableMembership(scope);
        boolean assignedOnly = membership != null;
        var permission = assignedOnly ? CompanyPermission.READ_ASSIGNED_ACTIONS : CompanyPermission.READ_ACTIONS;
        return work.execute(scope.actor(), scope.company(), permission, () -> {
            var action = entities.find(ImprovementActionRecord.class, id);
            if (action == null || !scope.company().equals(action.company)
                    || (assignedOnly && !membership.equals(action.assignee))) throw missing();
            return view(action);
        });
    }

    @Override
    public Action create(Scope scope, Draft draft) {
        return work.execute(scope.actor(), scope.company(), CompanyPermission.EDIT_ACTIONS, () -> {
            var membership = access.require(scope.company(), CompanyPermission.EDIT_ACTIONS);
            access.requireActionAssignee(scope.company(), draft.assigneeMembership());
            var data = draftData(draft);
            validate(data, membership.zonaHoraria());
            var now = Instant.now();
            var action = new ImprovementActionRecord(UUID.randomUUID(), scope.company(),
                    "AC-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(), data, scope.actor(), now);
            entities.persist(action);
            entities.flush();
            replaceContext(scope, action, draft.contextEvidence(), now);
            entities.persist(new ActionEvent(action, "CREADA", null, scope.actor(), now));
            entities.flush();
            record(scope, action, "CREAR_ACCION", null);
            return view(action);
        });
    }

    @Override
    public Action edit(Scope scope, UUID id, long revision, Draft draft, String reason) {
        return work.execute(scope.actor(), scope.company(), CompanyPermission.EDIT_ACTIONS, () -> {
            var action = editable(scope, id, revision);
            if (!"PENDIENTE".equals(action.state)) throw conflict();
            if (reason == null || reason.isBlank()) throw invalid();
            var membership = access.require(scope.company(), CompanyPermission.EDIT_ACTIONS);
            access.requireActionAssignee(scope.company(), draft.assigneeMembership());
            var data = draftData(draft);
            validate(data, membership.zonaHoraria());
            action.update(data, reason);
            replaceContext(scope, action, draft.contextEvidence(), Instant.now());
            entities.persist(new ActionEvent(action, "ACTUALIZADA", reason, scope.actor(), Instant.now()));
            entities.flush();
            record(scope, action, "ACTUALIZAR_ACCION", reason);
            return view(action);
        });
    }

    @Override
    public Action cancel(Scope scope, UUID id, long revision, String reason) {
        return work.execute(scope.actor(), scope.company(), CompanyPermission.EDIT_ACTIONS, () -> {
            var action = editable(scope, id, revision);
            if (!List.of("PENDIENTE", "EN_EJECUCION", "EN_REVISION").contains(action.state)
                    || reason == null || reason.isBlank()) throw invalid();
            String previous = action.state;
            Instant now = Instant.now();
            action.state = "CANCELADA";
            action.cancellationReason = reason;
            action.changeReason = reason;
            action.revision++;
            entities.persist(new ActionEvent(action, "CANCELADA", reason, scope.actor(), now));
            entities.flush();
            record(scope, action, "CANCELAR_ACCION", reason, previous);
            return view(action);
        });
    }

    @Override
    public void requireContextEvidence(Scope scope, UUID actionId, UUID evidenceId) {
        var membership = readableMembership(scope);
        var permission = membership == null ? CompanyPermission.READ_ACTIONS : CompanyPermission.READ_ASSIGNED_ACTIONS;
        work.execute(scope.actor(), scope.company(), permission, () -> {
            var action = entities.find(ImprovementActionRecord.class, actionId);
            if (action == null || !scope.company().equals(action.company)
                    || (membership != null && !membership.equals(action.assignee))) throw missing();
            long selected = entities.createQuery("""
                    select count(e) from ActionContextEvidence e
                    where e.company = :company and e.action = :action and e.evidence = :evidence
                    """, Long.class).setParameter("company", scope.company()).setParameter("action", actionId)
                    .setParameter("evidence", evidenceId).getSingleResult();
            if (selected == 0) throw missing();
            return null;
        });
    }

    private UUID readableMembership(Scope scope) {
        try {
            access.require(scope.company(), CompanyPermission.READ_ACTIONS);
            return null;
        } catch (AccessDeniedException denied) {
            return access.currentActionMembership(scope.company());
        }
    }

    private ImprovementActionRecord editable(Scope scope, UUID id, long revision) {
        var action = entities.find(ImprovementActionRecord.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (action == null || !scope.company().equals(action.company)) throw missing();
        if (action.revision != revision) throw conflict();
        return action;
    }

    private void validate(ImprovementActionRecord.DraftData data, String zoneName) {
        if ((data.risk() == null) == (data.finding() == null) || data.dueDate() == null
                || data.dueDate().isBefore(LocalDate.now(ZoneId.of(zoneName)))
                || data.description() == null || data.description().isBlank()
                || data.expectedResult() == null || data.expectedResult().isBlank()
                || data.contextSummary() == null || data.contextSummary().isBlank()
                || data.evidenceDescription() == null
                || data.evidenceDescription().isBlank()
                || (data.evidenceRequired() == (data.noEvidenceReason() != null && !data.noEvidenceReason().isBlank()))) {
            throw invalid();
        }
    }

    private ImprovementActionRecord.DraftData draftData(Draft draft) {
        if (draft.contextEvidence() == null || draft.contextEvidence().size() > 20
                || new HashSet<>(draft.contextEvidence().stream().map(ContextEvidence::evidenceId).toList()).size()
                    != draft.contextEvidence().size()
                || draft.contextEvidence().stream().anyMatch(item -> item.evidenceId() == null
                    || item.reason() == null || item.reason().isBlank())) throw invalid();
        return new ImprovementActionRecord.DraftData(draft.risk(), draft.finding(), clean(draft.description()),
                clean(draft.expectedResult()), draft.assigneeMembership(), draft.dueDate(), draft.evidenceRequired(),
                clean(draft.evidenceDescription()), clean(draft.noEvidenceReason()), clean(draft.contextSummary()));
    }

    private void replaceContext(Scope scope, ImprovementActionRecord action, List<ContextEvidence> context, Instant now) {
        var evidence = context.stream().map(ContextEvidence::evidenceId).toList();
        if (!evidence.isEmpty()) {
            long available = ((Number) entities.createNativeQuery("""
                    select count(*) from evidencias e
                    where e.empresa_id = :company and e.id in (:evidence)
                      and e.estado_carga = 'DISPONIBLE' and e.estado_inspeccion = 'LIMPIA'
                    """).setParameter("company", scope.company()).setParameter("evidence", evidence).getSingleResult()).longValue();
            if (available != evidence.size()) throw invalid();
        }
        entities.createNativeQuery("delete from accion_evidencias_contexto where empresa_id = :company and accion_id = :action")
                .setParameter("company", scope.company()).setParameter("action", action.id).executeUpdate();
        for (var item : context) entities.persist(new ActionContextEvidence(scope.company(), action.id,
                item.evidenceId(), clean(item.reason()), scope.actor(), now));
    }

    private Action view(ImprovementActionRecord action) {
        var context = entities.createQuery("""
                select e from ActionContextEvidence e where e.company = :company and e.action = :action
                order by e.created,e.id
                """, ActionContextEvidence.class).setParameter("company", action.company).setParameter("action", action.id)
                .getResultList().stream().map(e -> new ContextEvidenceView(e.evidence, e.reason)).toList();
        boolean overdue = !List.of("COMPLETADA", "CANCELADA").contains(action.state)
                && action.dueDate.isBefore(LocalDate.now(ZoneId.of(access.require(action.company,
                        CompanyPermission.READ_ASSIGNED_ACTIONS).zonaHoraria())));
        String assetName = action.risk == null ? null : (String) entities.createNativeQuery("""
                select a.nombre from riesgos r
                join activos a on a.empresa_id = r.empresa_id and a.id = r.activo_principal_id
                where r.empresa_id = :company and r.id = :risk
                """).setParameter("company", action.company).setParameter("risk", action.risk)
                .getResultStream().findFirst().orElse(null);
        return new Action(action.id, action.code, action.risk, action.finding, assetName, action.description, action.expectedResult,
                action.assignee, action.dueDate, action.state, overdue, action.evidenceRequired,
                action.evidenceDescription, action.noEvidenceReason, action.contextSummary, context, action.revision,
                action.changeReason, action.author, action.created);
    }

    private void record(Scope scope, ImprovementActionRecord action, String name, String reason) {
        record(scope, action, name, reason, "PENDIENTE");
    }

    private void record(Scope scope, ImprovementActionRecord action, String name, String reason, String previous) {
        audit.append(new ActivityLog.Event(scope.company(), scope.actor(), name, "ACCION", action.id,
                previous, action.state, reason, scope.correlation()));
    }

    private static String clean(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static AccessDeniedException missing() { return new AccessDeniedException("Acción no disponible."); }
    private static ActionFailure invalid() { return new ActionFailure(ActionFailure.Reason.INVALID); }
    private static OptimisticLockingFailureException conflict() { return new OptimisticLockingFailureException("La revisión de la acción cambió."); }
}
