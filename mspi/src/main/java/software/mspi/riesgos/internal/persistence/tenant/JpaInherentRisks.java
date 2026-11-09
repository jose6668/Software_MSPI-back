package software.mspi.riesgos.internal.persistence.tenant;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import software.mspi.activos.api.BusinessCode;
import software.mspi.auditoria.api.ActivityLog;
import software.mspi.catalogos.api.ReferenceCatalogs;
import software.mspi.catalogos.api.ReferenceNotFound;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;
import software.mspi.riesgos.api.InherentRisks;
import software.mspi.riesgos.api.RiskFailure;

import static software.mspi.riesgos.api.RiskFailure.Reason.*;

@Service
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
final class JpaInherentRisks implements InherentRisks {
    private final TenantUnitOfWork work;
    private final ReferenceCatalogs catalogs;
    private final ActivityLog audit;

    @PersistenceContext(unitName = "enterprise")
    private EntityManager entities;

    JpaInherentRisks(TenantUnitOfWork work, ReferenceCatalogs catalogs, ActivityLog audit) {
        this.work = work;
        this.catalogs = catalogs;
        this.audit = audit;
    }

    @Override
    public Page<Risk> list(Scope scope, String code, State state, int page, int size) {
        return read(scope, () -> {
            String filter = "select r from RiskRecord r where r.company = :company"
                    + (code == null ? "" : " and r.normalizedCode = :code")
                    + (state == null ? "" : " and r.state = :state")
                    + " order by r.created desc, r.id";
            var query = entities.createQuery(filter, RiskRecord.class).setParameter("company", scope.company())
                    .setLockMode(LockModeType.PESSIMISTIC_READ);
            if (code != null) query.setParameter("code", BusinessCode.normalize(code));
            if (state != null) query.setParameter("state", state);
            var rows = window(query, page, size);
            var levels = levels(scope.company(), rows);
            return page(rows, page, size, row -> row.view(row.current == null ? null : levels.get(row.current)));
        });
    }

    @Override
    public Risk get(Scope scope, UUID id) {
        return read(scope, () -> view(find(scope, id, LockModeType.PESSIMISTIC_READ)));
    }

    @Override
    public Risk create(Scope scope, String code, Details details) {
        return write(scope, CompanyPermission.EDIT_RISKS, () -> {
            requireAsset(scope.company(), details.activoPrincipalId());
            requireResponsible(scope.company(), details.responsableId());
            var risk = new RiskRecord(scope, code, details);
            entities.persist(risk);
            record(scope, risk.id, "RIESGO", "CREAR", null, risk.state.name(), null);
            return risk.view(null);
        });
    }

    @Override
    public Risk edit(Scope scope, UUID id, long revision, Details details, String reason) {
        return write(scope, CompanyPermission.EDIT_RISKS, () -> {
            var risk = editable(scope, id, revision);
            if (!risk.asset.equals(details.activoPrincipalId())) requireAsset(scope.company(), details.activoPrincipalId());
            if (!risk.responsible.equals(details.responsableId())) requireResponsible(scope.company(), details.responsableId());
            if (risk.changesBasis(details)) {
                if (risk.current != null && reason == null) throw new RiskFailure(REASON);
                risk.context++;
                if (risk.current != null) risk.reviewReason = reason;
            }
            risk.update(details);
            risk.revision++;
            record(scope, id, "RIESGO", "EDITAR", risk.state.name(), risk.state.name(), reason);
            return view(risk);
        });
    }

    @Override
    public Risk startAnalysis(Scope scope, UUID id, long revision) {
        return write(scope, CompanyPermission.EDIT_RISKS, () -> {
            var risk = editable(scope, id, revision);
            requireState(risk, State.IDENTIFICADO);
            requireResponsible(scope.company(), risk.responsible);
            risk.state = State.EN_ANALISIS;
            risk.revision++;
            record(scope, id, "RIESGO", "INICIAR_ANALISIS", State.IDENTIFICADO.name(), State.EN_ANALISIS.name(), null);
            return view(risk);
        });
    }

    @Override
    public Page<Assessment> assessments(Scope scope, UUID id, int page, int size) {
        return read(scope, () -> {
            var risk = find(scope, id, LockModeType.PESSIMISTIC_READ);
            var query = entities.createQuery("""
                    select v from InherentAssessment v where v.company = :company and v.risk = :risk
                    order by v.created desc, v.id
                    """, InherentAssessment.class).setParameter("company", scope.company()).setParameter("risk", id);
            return page(window(query, page, size), page, size, row -> row.view(risk));
        });
    }

    @Override
    public Assessment startAssessment(Scope scope, UUID id, long revision, Inputs inputs, String reason) {
        return write(scope, CompanyPermission.EDIT_RISKS, () -> {
            var risk = editable(scope, id, revision);
            requireState(risk, State.EN_ANALISIS);
            if ((risk.current == null) != (reason == null)) throw new RiskFailure(REASON);
            if (!methodology(scope.company(), RiskMatrix.V1).disponibleNuevos()) throw new RiskFailure(METHODOLOGY);
            var assessment = new InherentAssessment(risk, RiskMatrix.V1, scope.actor(), inputs, reason);
            entities.persist(assessment);
            risk.revision++;
            record(scope, assessment.id, "VALORACION_INHERENTE", "INICIAR_VALORACION", null, "BORRADOR", reason);
            return assessment.view(risk);
        });
    }

    @Override
    public Assessment editAssessment(Scope scope, UUID id, UUID assessmentId, long revision, Inputs inputs) {
        return write(scope, CompanyPermission.EDIT_RISKS, () -> {
            var risk = editable(scope, id, revision);
            var assessment = draft(risk, assessmentId);
            assessment.update(inputs, risk.context);
            risk.revision++;
            record(scope, assessmentId, "VALORACION_INHERENTE", "EDITAR_VALORACION", "BORRADOR", "BORRADOR", null);
            return assessment.view(risk);
        });
    }

    @Override
    public Assessment confirmAssessment(Scope scope, UUID id, UUID assessmentId, long revision) {
        return write(scope, CompanyPermission.EDIT_RISKS, () -> {
            var risk = editable(scope, id, revision);
            var assessment = draft(risk, assessmentId);
            if (assessment.context != risk.context) throw new RiskFailure(STALE);
            var missing = assessment.missing();
            if (!missing.isEmpty()) throw new RiskFailure(INCOMPLETE, missing);
            var rules = methodology(scope.company(), assessment.method).reglas();
            assessment.confirm(risk, RiskMatrix.calculate(rules, assessment.probability, assessment.impact), scope.actor());
            risk.current = assessment.id;
            risk.reviewReason = null;
            risk.revision++;
            record(scope, assessmentId, "VALORACION_INHERENTE", "CONFIRMAR_VALORACION", "BORRADOR", "CONFIRMADA", null);
            return assessment.view(risk);
        });
    }

    @Override
    public Page<Link> links(Scope scope, UUID id, int page, int size) {
        return read(scope, () -> {
            var risk = find(scope, id, LockModeType.PESSIMISTIC_READ);
            var query = entities.createQuery("""
                    select l from RiskControlLink l where l.company = :company and l.risk = :risk order by l.created, l.id
                    """, RiskControlLink.class).setParameter("company", scope.company()).setParameter("risk", id);
            return page(window(query, page, size), page, size, row -> row.view(risk.revision));
        });
    }

    @Override
    public Link link(Scope scope, UUID id, long revision, UUID implementation, Effect effect, String rationale) {
        return write(scope, CompanyPermission.EDIT_CONTROLS, () -> {
            var risk = editable(scope, id, revision);
            if (entities.createNativeQuery("""
                    select id from implementaciones_control where empresa_id = :company and id = :id
                    """, UUID.class).setParameter("company", scope.company()).setParameter("id", implementation)
                    .getResultList().isEmpty()) throw new RiskFailure(IMPLEMENTATION);
            var link = new RiskControlLink(risk, implementation, effect, rationale, scope.actor());
            entities.persist(link);
            risk.revision++;
            record(scope, link.id, "VINCULO_RIESGO_CONTROL", "VINCULAR_CONTROL", null, null, null);
            return link.view(risk.revision);
        });
    }

    private RiskRecord find(Scope scope, UUID id, LockModeType lock) {
        var risk = entities.find(RiskRecord.class, id, lock);
        if (risk == null || !scope.company().equals(risk.company)) throw new RiskFailure(MISSING);
        return risk;
    }

    private RiskRecord editable(Scope scope, UUID id, long revision) {
        var risk = find(scope, id, LockModeType.PESSIMISTIC_WRITE);
        if (risk.revision != revision) throw new OptimisticLockingFailureException("Revisión desactualizada.");
        return risk;
    }

    private static void requireState(RiskRecord risk, State state) {
        if (risk.state != state) throw new RiskFailure(STATE);
    }

    private InherentAssessment draft(RiskRecord risk, UUID id) {
        requireState(risk, State.EN_ANALISIS);
        var assessment = entities.find(InherentAssessment.class, id);
        if (assessment == null || !risk.company.equals(assessment.company) || !risk.id.equals(assessment.risk)) {
            throw new RiskFailure(MISSING);
        }
        if (assessment.confirmedAt != null) throw new RiskFailure(STATE);
        return assessment;
    }

    private Risk view(RiskRecord risk) {
        return risk.view(risk.current == null ? null : entities.find(InherentAssessment.class, risk.current).level);
    }

    private Map<UUID, Level> levels(UUID company, List<RiskRecord> rows) {
        var ids = rows.stream().map(row -> row.current).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return Map.of();
        return entities.createQuery("""
                select v from InherentAssessment v where v.company = :company and v.id in :ids
                """, InherentAssessment.class).setParameter("company", company).setParameter("ids", ids)
                .getResultList().stream().collect(Collectors.toMap(row -> row.id, row -> row.level));
    }

    private ReferenceCatalogs.MethodVersion methodology(UUID company, UUID id) {
        try {
            var method = catalogs.methodology(company, id);
            if ("RIESGO_CATEGORICO_V1".equals(method.identificador()) && method.aprobadaEn() != null) return method;
        } catch (ReferenceNotFound missing) {
            throw new RiskFailure(METHODOLOGY);
        }
        throw new RiskFailure(METHODOLOGY);
    }

    private void requireAsset(UUID company, UUID id) {
        if (entities.createNativeQuery("""
                select id from activos where empresa_id = :company and id = :id and estado = 'ACTIVO' for share
                """, UUID.class).setParameter("company", company).setParameter("id", id).getResultList().isEmpty()) {
            throw new RiskFailure(ASSET);
        }
    }

    private void requireResponsible(UUID company, UUID id) {
        if (entities.createNativeQuery("""
                select id from responsables_negocio where empresa_id = :company and id = :id and estado = 'ACTIVO' for share
                """, UUID.class).setParameter("company", company).setParameter("id", id).getResultList().isEmpty()) {
            throw new RiskFailure(RESPONSIBLE);
        }
    }

    private void record(Scope scope, UUID id, String type, String action, String from, String to, String reason) {
        entities.flush();
        audit.append(new ActivityLog.Event(scope.company(), scope.actor(), action, type, id, from, to, reason, scope.correlation()));
    }

    private <T> T read(Scope scope, Supplier<T> task) {
        return work.execute(scope.actor(), scope.company(), CompanyPermission.READ_RISKS, task);
    }

    private <T> T write(Scope scope, CompanyPermission permission, Supplier<T> task) {
        try {
            return work.execute(scope.actor(), scope.company(), permission, task);
        } catch (ConstraintViolationException failure) {
            throw switch (failure.getConstraintName() == null ? "" : failure.getConstraintName()) {
                case "riesgo_codigo_local" -> new RiskFailure(CODE);
                case "riesgo_control_unico" -> new RiskFailure(DUPLICATE_LINK);
                case "riesgo_un_borrador_inherente" -> new RiskFailure(DRAFT_EXISTS);
                default -> failure;
            };
        }
    }

    private static <T> List<T> window(TypedQuery<T> query, int page, int size) {
        long offset = (long) page * size;
        return offset > Integer.MAX_VALUE ? List.of() : query.setFirstResult((int) offset).setMaxResults(size + 1).getResultList();
    }

    private static <T, R> Page<R> page(List<T> rows, int page, int size, Function<T, R> mapper) {
        return new Page<>(rows.stream().limit(size).map(mapper).toList(), page, size, rows.size() > size);
    }
}
