package software.mspi.riesgos.internal.persistence.tenant;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import software.mspi.empresas.api.CompanyAccess;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;
import software.mspi.riesgos.api.InherentRisks;
import software.mspi.riesgos.api.ResidualRisks;
import software.mspi.riesgos.api.RiskFailure;

import static software.mspi.riesgos.api.RiskFailure.Reason.*;

@Service
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
final class JpaInherentRisks implements InherentRisks, ResidualRisks {
    private final TenantUnitOfWork work;
    private final ReferenceCatalogs catalogs;
    private final ActivityLog audit;
    private final CompanyAccess access;

    @PersistenceContext(unitName = "enterprise")
    private EntityManager entities;

    JpaInherentRisks(TenantUnitOfWork work, ReferenceCatalogs catalogs, ActivityLog audit, CompanyAccess access) {
        this.work = work;
        this.catalogs = catalogs;
        this.audit = audit;
        this.access = access;
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
        return register(scope, null, code, details);
    }

    @Override
    public Risk createRelated(Scope scope, UUID original, String code, Details details) {
        return register(scope, original, code, details);
    }

    private Risk register(Scope scope, UUID original, String code, Details details) {
        return write(scope, CompanyPermission.EDIT_RISKS, () -> {
            if (original != null) requireState(find(scope, original, LockModeType.PESSIMISTIC_READ), State.CERRADO);
            requireAsset(scope.company(), details.activoPrincipalId());
            requireResponsible(scope.company(), details.responsableId());
            var risk = new RiskRecord(scope, code, details);
            risk.related = original;
            entities.persist(risk);
            record(scope, risk.id, "RIESGO", "CREAR", null, risk.state.name(), null);
            return risk.view(null);
        });
    }

    @Override
    public Risk edit(Scope scope, UUID id, long revision, Details details, String reason) {
        return write(scope, CompanyPermission.EDIT_RISKS, () -> {
            var risk = editable(scope, id, revision);
            if ((risk.state == State.EN_SEGUIMIENTO || risk.state == State.EN_TRATAMIENTO)
                    && (risk.changesBasis(details) || !Objects.equals(risk.treatment, details.tratamientoPropuesto()))) {
                throw new RiskFailure(STATE);
            }
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

    @Override
    public Page<Residual> residuals(Scope scope, UUID id, int page, int size) {
        return read(scope, () -> {
            var risk = find(scope, id, LockModeType.PESSIMISTIC_READ);
            var rows = window(entities.createQuery("""
                    select v from ResidualAssessment v where v.company = :company and v.risk = :risk
                    order by v.created desc, v.id
                    """, ResidualAssessment.class).setParameter("company", scope.company()).setParameter("risk", id), page, size);
            var results = new ResidualDependencies(entities).views(risk, rows.stream().limit(size).toList());
            return new Page<>(results, page, size, rows.size() > size);
        });
    }

    @Override
    public Residual startResidual(Scope scope, UUID id, long revision, Draft draft, String reason) {
        return write(scope, CompanyPermission.EDIT_RISKS, () -> {
            var risk = editable(scope, id, revision);
            var base = requireInherent(risk);
            if ((risk.residual == null) != (reason == null)) throw new RiskFailure(REASON);
            requireDistinct(draft);
            var residual = new ResidualAssessment(risk, base, scope.actor(), draft, reason);
            entities.persist(residual);
            risk.revision++;
            record(scope, residual.id, "VALORACION_RESIDUAL", "INICIAR_RESIDUAL", null, "BORRADOR", reason);
            return residual.view(risk, List.of(), List.of());
        });
    }

    @Override
    public Residual editResidual(Scope scope, UUID id, UUID residualId, long revision, Draft draft) {
        return write(scope, CompanyPermission.EDIT_RISKS, () -> {
            var risk = editable(scope, id, revision);
            var residual = residualDraft(risk, residualId);
            requireDistinct(draft);
            residual.update(requireInherent(risk), draft);
            risk.revision++;
            record(scope, residual.id, "VALORACION_RESIDUAL", "EDITAR_RESIDUAL", "BORRADOR", "BORRADOR", null);
            return residual.view(risk, List.of(), List.of());
        });
    }

    @Override
    public Residual confirmResidual(Scope scope, UUID id, UUID residualId, long revision) {
        return write(scope, CompanyPermission.EDIT_RISKS, () -> {
            var risk = editable(scope, id, revision);
            var residual = residualDraft(risk, residualId);
            var base = requireInherent(risk);
            if (!base.id.equals(residual.inherent)) throw new RiskFailure(BASE_INHERENTE);
            var missing = RiskMatrix.missing(residual.inputs());
            if (!missing.isEmpty()) throw new RiskFailure(RESIDUAL_INCOMPLETE, missing);

            ZoneId zone = ZoneId.of(access.require(scope.company(), CompanyPermission.EDIT_RISKS).zonaHoraria());
            var dependencies = new ResidualDependencies(entities);
            dependencies.lockLinkedControls(risk);
            var proofs = requireMitigation(risk, residual, base, zone, dependencies);
            var rules = methodology(scope.company(), residual.method).reglas();
            var result = RiskMatrix.calculate(rules, residual.probability, residual.impact);
            for (var proof : proofs) entities.persist(new ResidualControl(residual, proof));
            entities.flush();

            residual.level = result.level();
            residual.probabilityIndex = result.probabilityIndex();
            residual.impactIndex = result.impactIndex();
            residual.confirmedBy = scope.actor();
            residual.confirmedAt = Instant.now();
            if (proofs.stream().anyMatch(proof -> !proof.venceEn().isAfter(residual.confirmedAt))) {
                throw new RiskFailure(RESIDUAL_PROOF);
            }
            risk.residual = residual.id;
            risk.revision++;
            record(scope, residual.id, "VALORACION_RESIDUAL", "CONFIRMAR_RESIDUAL", "BORRADOR", "CONFIRMADA", null);
            return residual.view(risk, proofs, List.of());
        });
    }

    private static void requireDistinct(Draft draft) {
        if (new HashSet<>(draft.verificaciones()).size() != draft.verificaciones().size()) {
            throw new RiskFailure(RESIDUAL_PROOF);
        }
    }

    private InherentAssessment requireInherent(RiskRecord risk) {
        requireAssessmentWork(risk);
        return currentInherent(risk);
    }

    private InherentAssessment currentInherent(RiskRecord risk) {
        if (risk.current == null || risk.reviewReason != null) throw new RiskFailure(BASE_INHERENTE);
        var base = entities.find(InherentAssessment.class, risk.current);
        if (base == null || base.confirmedAt == null || base.context != risk.context) throw new RiskFailure(BASE_INHERENTE);
        return base;
    }

    @Override
    public Acceptance accept(Scope scope, UUID id, long revision, String reason, LocalDate reviewDate) {
        return write(scope, CompanyPermission.ACCEPT_RISK, () -> {
            var risk = editable(scope, id, revision);
            if (risk.treatment != Treatment.ACEPTAR) throw new RiskFailure(STATE);
            requireReason(reason);
            requireCurrentAssessments(risk);
            var now = Instant.now();
            if (reviewDate == null || !reviewDate.isAfter(now.atZone(zone(scope)).toLocalDate())) {
                throw new RiskFailure(ACCEPTANCE_DATE);
            }
            risk.revision++;
            var acceptance = new RiskAcceptance(risk, scope.actor(), reason, reviewDate, now);
            entities.persist(acceptance);
            record(scope, acceptance.id, "ACEPTACION_RIESGO", "ACEPTAR_RIESGO", null, "VIGENTE", reason);
            return acceptance.view("VIGENTE");
        });
    }

    @Override
    public Page<Acceptance> acceptances(Scope scope, UUID id, int page, int size) {
        return read(scope, () -> {
            var risk = find(scope, id, LockModeType.PESSIMISTIC_READ);
            var current = latestAcceptance(risk);
            var rows = window(acceptanceQuery(risk), page, size);
            boolean valid = acceptanceCurrent(risk, current, zone(scope));
            return page(rows, page, size, row -> row.view(!row.id.equals(current.id)
                    ? "HISTORICA" : valid ? "VIGENTE" : "REVISION_PENDIENTE"));
        });
    }

    @Override
    public Risk transition(Scope scope, UUID id, long revision, State destination, String reason,
                           Map<UUID, String> cancellations) {
        return write(scope, CompanyPermission.TRANSITION_RISK, () -> {
            var risk = editable(scope, id, revision);
            requireReason(reason);
            State previous = risk.state;
            if (destination != State.CERRADO && !cancellations.isEmpty()) throw new RiskFailure(CANCELLATION_EXPLANATION);
            switch (destination) {
                case EN_ANALISIS -> {
                    requireState(risk, State.EN_SEGUIMIENTO);
                    risk.context++;
                    risk.reviewReason = reason;
                }
                case EN_TRATAMIENTO -> {
                    if (previous != State.EN_ANALISIS && previous != State.EN_SEGUIMIENTO) throw new RiskFailure(STATE);
                    currentInherent(risk);
                    if (previous == State.EN_SEGUIMIENTO) requireCurrentAssessments(risk);
                    if (risk.treatment == null || actionStatus(risk).open().isEmpty()) throw new RiskFailure(PLAN_REQUIRED);
                    requireApplicableAcceptance(risk, zone(scope));
                }
                case EN_SEGUIMIENTO -> {
                    if (previous != State.EN_ANALISIS && previous != State.EN_TRATAMIENTO) throw new RiskFailure(STATE);
                    if (risk.treatment == null) throw new RiskFailure(PLAN_REQUIRED);
                    requireCurrentAssessments(risk);
                    var actions = actionStatus(risk);
                    if (!actions.open().isEmpty() || !actions.unjustified().isEmpty()) throw new RiskFailure(OPEN_ACTIONS);
                    requireApplicableAcceptance(risk, zone(scope));
                }
                case CERRADO -> {
                    requireState(risk, State.EN_SEGUIMIENTO);
                    requireCurrentAssessments(risk);
                    requireNoPendingAssessments(risk);
                    var actions = actionStatus(risk);
                    if (!actions.open().isEmpty() || !actions.unjustified().isEmpty()) throw new RiskFailure(OPEN_ACTIONS);
                    if (!actions.cancelled().equals(cancellations.keySet())
                            || cancellations.values().stream().anyMatch(value -> value == null || value.isBlank())) {
                        throw new RiskFailure(CANCELLATION_EXPLANATION);
                    }
                    var acceptance = requireApplicableAcceptance(risk, zone(scope));
                    risk.closureAcceptance = acceptance == null ? null : acceptance.id;
                    risk.closureReason = reason;
                    risk.closedAt = Instant.now();
                    risk.closedBy = scope.actor();
                    risk.closureCancellations = cancellations.entrySet().stream()
                            .collect(Collectors.toMap(entry -> entry.getKey().toString(), entry -> entry.getValue().strip()));
                }
                default -> throw new RiskFailure(STATE);
            }
            risk.state = destination;
            risk.revision++;
            record(scope, id, "RIESGO", "TRANSICION_RIESGO", previous.name(), destination.name(), reason);
            return view(risk);
        });
    }

    private void requireCurrentAssessments(RiskRecord risk) {
        var dependencies = new ResidualDependencies(entities);
        dependencies.lockLinkedControls(risk);
        if (!assessmentsCurrent(risk)) throw new RiskFailure(LIFECYCLE_ASSESSMENT);
        var residual = entities.find(ResidualAssessment.class, risk.residual);
        if (residual.selected.length != 0) {
            dependencies.verify(risk, List.of(residual.selected),
                    ZoneId.of(access.require(risk.company, CompanyPermission.READ_RISKS).zonaHoraria()));
        }
    }

    private void requireNoPendingAssessments(RiskRecord risk) {
        boolean pending = entities.createQuery("""
                select count(v) from InherentAssessment v where v.company = :company and v.risk = :risk and v.confirmedAt is null
                """, Long.class).setParameter("company", risk.company).setParameter("risk", risk.id).getSingleResult() != 0
                || entities.createQuery("""
                select count(v) from ResidualAssessment v where v.company = :company and v.risk = :risk and v.confirmedAt is null
                """, Long.class).setParameter("company", risk.company).setParameter("risk", risk.id).getSingleResult() != 0;
        if (pending) throw new RiskFailure(LIFECYCLE_ASSESSMENT);
    }

    private boolean assessmentsCurrent(RiskRecord risk) {
        if (risk.current == null || risk.residual == null || risk.reviewReason != null) return false;
        var inherent = entities.find(InherentAssessment.class, risk.current);
        var residual = entities.find(ResidualAssessment.class, risk.residual);
        return inherent != null && inherent.confirmedAt != null && inherent.context == risk.context
                && residual != null && residual.confirmedAt != null && risk.current.equals(residual.inherent)
                && new ResidualDependencies(entities).views(risk, List.of(residual)).get(0).vigente();
    }

    private TypedQuery<RiskAcceptance> acceptanceQuery(RiskRecord risk) {
        return entities.createQuery("""
                select a from RiskAcceptance a where a.company = :company and a.risk = :risk order by a.revision desc
                """, RiskAcceptance.class).setParameter("company", risk.company).setParameter("risk", risk.id);
    }

    private RiskAcceptance latestAcceptance(RiskRecord risk) {
        return acceptanceQuery(risk).setMaxResults(1).getResultList().stream().findFirst().orElse(null);
    }

    private RiskAcceptance requireApplicableAcceptance(RiskRecord risk, ZoneId zone) {
        if (risk.treatment != Treatment.ACEPTAR) return null;
        var acceptance = latestAcceptance(risk);
        if (!acceptanceCurrent(risk, acceptance, zone)) throw new RiskFailure(ACCEPTANCE_REQUIRED);
        return acceptance;
    }

    private boolean acceptanceCurrent(RiskRecord risk, RiskAcceptance acceptance, ZoneId zone) {
        return acceptance != null && acceptance.reviewDate.isAfter(LocalDate.now(zone))
                && acceptance.inherent.equals(risk.current) && acceptance.residual.equals(risk.residual)
                && assessmentsCurrent(risk);
    }

    private ZoneId zone(Scope scope) {
        return ZoneId.of(access.require(scope.company(), CompanyPermission.READ_RISKS).zonaHoraria());
    }

    private ActionStatus actionStatus(RiskRecord risk) {
        var rows = entities.unwrap(org.hibernate.Session.class).createNativeQuery("""
                select id, estado, motivo_cancelacion from acciones_mejora where empresa_id = :company and riesgo_id = :risk
                """, Object[].class).setParameter("company", risk.company).setParameter("risk", risk.id).getResultList();
        var open = new HashSet<UUID>();
        var cancelled = new HashSet<UUID>();
        var unjustified = new HashSet<UUID>();
        for (var row : rows) {
            UUID id = (UUID) row[0];
            if ("CANCELADA".equals(row[1])) {
                cancelled.add(id);
                if (row[2] == null || row[2].toString().isBlank()) unjustified.add(id);
            } else if (!"COMPLETADA".equals(row[1])) {
                open.add(id);
            }
        }
        return new ActionStatus(open, cancelled, unjustified);
    }

    private record ActionStatus(java.util.Set<UUID> open, java.util.Set<UUID> cancelled, java.util.Set<UUID> unjustified) {}

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) throw new RiskFailure(REASON);
    }

    private ResidualAssessment residualDraft(RiskRecord risk, UUID id) {
        requireAssessmentWork(risk);
        var residual = entities.find(ResidualAssessment.class, id);
        if (residual == null || !risk.company.equals(residual.company) || !risk.id.equals(residual.risk)) {
            throw new RiskFailure(MISSING);
        }
        if (residual.confirmedAt != null) throw new RiskFailure(STATE);
        return residual;
    }

    private static void requireAssessmentWork(RiskRecord risk) {
        if (risk.state != State.EN_ANALISIS && risk.state != State.EN_TRATAMIENTO) throw new RiskFailure(STATE);
    }

    private List<Proof> requireMitigation(RiskRecord risk, ResidualAssessment residual, InherentAssessment base, ZoneId zone,
                                          ResidualDependencies dependencies) {
        if (residual.withoutMitigation == null) throw new RiskFailure(RESIDUAL_MITIGATION);
        if (residual.withoutMitigation) {
            if (residual.absenceReason == null || residual.selected.length != 0
                    || residual.probability != base.probability || residual.impact != base.impact) {
                throw new RiskFailure(RESIDUAL_MITIGATION);
            }
            return List.of();
        }
        if (residual.absenceReason != null || residual.selected.length == 0) throw new RiskFailure(RESIDUAL_MITIGATION);
        return dependencies.verify(risk, List.of(residual.selected), zone);
    }

    private RiskRecord find(Scope scope, UUID id, LockModeType lock) {
        var risk = entities.find(RiskRecord.class, id, lock);
        if (risk == null || !scope.company().equals(risk.company)) throw new RiskFailure(MISSING);
        return risk;
    }

    private RiskRecord editable(Scope scope, UUID id, long revision) {
        var risk = find(scope, id, LockModeType.PESSIMISTIC_WRITE);
        if (risk.revision != revision) throw new OptimisticLockingFailureException("Revisión desactualizada.");
        if (risk.state == State.CERRADO) throw new RiskFailure(STATE);
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
                case "riesgo_un_borrador_residual" -> new RiskFailure(RESIDUAL_DRAFT_EXISTS);
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
