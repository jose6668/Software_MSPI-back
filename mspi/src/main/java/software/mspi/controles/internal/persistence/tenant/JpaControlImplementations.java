package software.mspi.controles.internal.persistence.tenant;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.hibernate.JDBCException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import software.mspi.auditoria.api.ActivityLog;
import software.mspi.catalogos.api.ReferenceCatalogs;
import software.mspi.catalogos.api.ReferenceNotFound;
import software.mspi.controles.api.ControlFailure;
import software.mspi.controles.api.ControlImplementations;
import software.mspi.empresas.api.CompanyAccess;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;

import static software.mspi.controles.api.ControlFailure.Reason.*;
import static software.mspi.controles.api.ControlImplementations.State.*;

@Service
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
final class JpaControlImplementations implements ControlImplementations {
    private final TenantUnitOfWork work;
    private final CompanyAccess access;
    private final ReferenceCatalogs catalogs;
    private final ActivityLog audit;

    @PersistenceContext(unitName = "enterprise")
    private EntityManager entities;

    JpaControlImplementations(TenantUnitOfWork work, CompanyAccess access, ReferenceCatalogs catalogs, ActivityLog audit) {
        this.work = work;
        this.access = access;
        this.catalogs = catalogs;
        this.audit = audit;
    }

    @Override
    public Page<Implementation> list(Scope scope, int page, int size) {
        ZoneId zone = zone(scope, CompanyPermission.READ_CONTROLS);
        return read(scope, () -> {
            var rows = window(entities.createQuery("""
                    select i from ImplementationRecord i where i.company = :company order by i.created desc, i.id
                    """, ImplementationRecord.class).setParameter("company", scope.company())
                    .setLockMode(LockModeType.PESSIMISTIC_READ), page, size);
            var selected = rows.stream().limit(size).toList();
            return new Page<>(views(scope.company(), selected, LocalDate.now(zone)), page, size, rows.size() > size);
        });
    }

    @Override
    public Implementation get(Scope scope, UUID id) {
        ZoneId zone = zone(scope, CompanyPermission.READ_CONTROLS);
        return read(scope, () -> view(find(scope, id, LockModeType.PESSIMISTIC_READ), zone));
    }

    @Override
    public Page<Version> versions(Scope scope, UUID id, int page, int size) {
        return read(scope, () -> {
            var root = find(scope, id, LockModeType.NONE);
            var rows = window(entities.createQuery("""
                    select v from ControlVersion v where v.company = :company and v.implementation = :id
                    order by v.number desc
                    """, ControlVersion.class).setParameter("company", root.company).setParameter("id", id), page, size);
            var applications = applications(root.company, rows);
            return page(rows, page, size, row -> versionView(row, applications));
        });
    }

    @Override
    public Page<Verification> verifications(Scope scope, UUID id, UUID version, int page, int size) {
        return read(scope, () -> {
            requireVersion(scope, id, version);
            var rows = window(entities.createQuery("""
                    select v from ControlVerification v
                    where v.company = :company and v.implementation = :id and v.version = :version
                    order by v.revision desc
                    """, ControlVerification.class).setParameter("company", scope.company())
                    .setParameter("id", id).setParameter("version", version), page, size);
            return page(rows, page, size, ControlVerification::view);
        });
    }

    @Override
    public Implementation create(Scope scope, UUID catalog, UUID element, Details details) {
        ZoneId zone = zone(scope, CompanyPermission.EDIT_CONTROLS);
        return write(scope, () -> {
            requireCatalog(scope.company(), catalog, element);
            requireResponsible(scope.company(), details.responsableId());
            var root = new ImplementationRecord(scope, catalog, element);
            entities.persist(root);
            entities.flush();
            entities.persist(new ControlVersion(root, details, null, scope.actor()));
            record(scope, root.id, "IMPLEMENTACION_CONTROL", "CREAR", null, root.state.name(), null);
            return view(root, zone);
        });
    }

    @Override
    public Implementation start(Scope scope, UUID id, long revision) {
        ZoneId zone = zone(scope, CompanyPermission.EDIT_CONTROLS);
        return write(scope, () -> {
            var root = editable(scope, id, revision, PROPUESTO);
            requireResponsible(scope.company(), current(root).responsible);
            advance(scope, root, EN_IMPLEMENTACION, "INICIAR_IMPLEMENTACION", null);
            return view(root, zone);
        });
    }

    @Override
    public Implementation apply(Scope scope, UUID id, long revision, LocalDate date, UUID evidence, EvidenceVerifier verifier) {
        ZoneId zone = zone(scope, CompanyPermission.EDIT_CONTROLS);
        if (date.isAfter(LocalDate.now(zone))) throw new ControlFailure(DATES);
        var proof = inspect(scope, id, revision, EN_IMPLEMENTACION, evidence, verifier);
        return write(scope, () -> {
            var root = editable(scope, id, revision, EN_IMPLEMENTACION);
            var version = current(root);
            requireResponsible(scope.company(), version.responsible);
            entities.persist(new ControlApplication(version, date, evidence, proof, scope.actor()));
            advance(scope, root, IMPLEMENTADO, "CONFIRMAR_APLICACION", null);
            return view(root, zone);
        });
    }

    @Override
    public Implementation change(Scope scope, UUID id, long revision, Details details, String reason) {
        ZoneId zone = zone(scope, CompanyPermission.EDIT_CONTROLS);
        return write(scope, () -> {
            var root = editable(scope, id, revision, IMPLEMENTADO);
            requireResponsible(scope.company(), details.responsableId());
            root.number++;
            entities.persist(new ControlVersion(root, details, reason, scope.actor()));
            advance(scope, root, EN_IMPLEMENTACION, "CAMBIAR_IMPLEMENTACION", reason);
            return view(root, zone);
        });
    }

    @Override
    public Verification verify(Scope scope, UUID id, long revision, Test input, EvidenceVerifier verifier) {
        ZoneId zone = zone(scope, CompanyPermission.EDIT_CONTROLS);
        if (input.criteriosEvaluados() < 1 || input.criteriosCumplidos() < 0
                || input.criteriosCumplidos() > input.criteriosEvaluados()) throw new ControlFailure(CRITERIA);
        if (!input.proximaRevision().isAfter(LocalDate.now(zone))) throw new ControlFailure(DATES);
        String proof = inspect(scope, id, revision, IMPLEMENTADO, input.evidenciaId(), verifier);
        return write(scope, () -> {
            LocalDate today = LocalDate.now(zone);
            if (!input.proximaRevision().isAfter(today)) throw new ControlFailure(DATES);
            var root = editable(scope, id, revision, IMPLEMENTADO);
            var version = current(root);
            var previous = latest(scope.company(), List.of(version)).get(version.id);
            root.revision++;
            var verification = new ControlVerification(version, root.revision, input, today, proof, scope.actor());
            entities.persist(verification);
            record(scope, verification.id, "VERIFICACION_CONTROL", "VERIFICAR",
                    previous == null ? Result.SIN_VERIFICAR.name() : previous.result().name(), verification.result().name(), null);
            return verification.view();
        });
    }

    @Override
    public void requireVersion(Scope scope, UUID id, UUID versionId) {
        read(scope, () -> {
            var version = entities.find(ControlVersion.class, versionId);
            if (version == null || !scope.company().equals(version.company) || !id.equals(version.implementation)) {
                throw new ControlFailure(MISSING);
            }
            return null;
        });
    }

    @Override
    public <T> Optional<T> attachEvidence(Scope scope, UUID id, UUID version, long revision, Supplier<Optional<T>> publish) {
        return write(scope, () -> {
            var root = editable(scope, id, revision, null);
            if (root.state == PROPUESTO || !current(root).id.equals(version)) throw new ControlFailure(STATE);
            var result = publish.get();
            if (result.isPresent()) root.revision++;
            return result;
        });
    }

    private String inspect(Scope scope, UUID id, long revision, State state, UUID evidence, EvidenceVerifier verifier) {
        UUID version = write(scope, () -> current(editable(scope, id, revision, state)).id);
        return verifier.verifiedHash(scope, id, version, evidence).orElseThrow(() -> new ControlFailure(EVIDENCE));
    }

    private ImplementationRecord editable(Scope scope, UUID id, long revision, State required) {
        var root = find(scope, id, LockModeType.PESSIMISTIC_WRITE);
        if (root.revision != revision) throw new OptimisticLockingFailureException("Revisión desactualizada.");
        if (required != null && root.state != required) throw new ControlFailure(STATE);
        return root;
    }

    private ImplementationRecord find(Scope scope, UUID id, LockModeType lock) {
        var root = entities.find(ImplementationRecord.class, id, lock);
        if (root == null || !scope.company().equals(root.company)) throw new ControlFailure(MISSING);
        return root;
    }

    private ControlVersion current(ImplementationRecord root) {
        return entities.createQuery("""
                select v from ControlVersion v where v.company = :company and v.implementation = :id and v.number = :number
                """, ControlVersion.class).setParameter("company", root.company).setParameter("id", root.id)
                .setParameter("number", root.number).getSingleResult();
    }

    private Implementation view(ImplementationRecord root, ZoneId zone) {
        return views(root.company, List.of(root), LocalDate.now(zone)).get(0);
    }

    private List<Implementation> views(UUID company, List<ImplementationRecord> roots, LocalDate today) {
        if (roots.isEmpty()) return List.of();
        var ids = roots.stream().map(root -> root.id).toList();
        var versions = entities.createQuery("""
                select v from ControlVersion v, ImplementationRecord i
                where i.company = :company and i.id in :ids and v.company = i.company
                and v.implementation = i.id and v.number = i.number
                """, ControlVersion.class).setParameter("company", company).setParameter("ids", ids).getResultList();
        var byRoot = versions.stream().collect(Collectors.toMap(version -> version.implementation, Function.identity()));
        var applications = applications(company, versions);
        var verifications = latest(company, versions);
        var evidence = new java.util.HashSet<UUID>();
        applications.values().forEach(application -> evidence.add(application.evidence));
        verifications.values().forEach(verification -> evidence.add(verification.evidence));
        Set<?> blocked = evidence.isEmpty() ? Set.of() : Set.copyOf(entities.createNativeQuery("""
                select id from evidencias where empresa_id = :company and id in (:ids) and estado_inspeccion = 'BLOQUEADA'
                """, UUID.class).setParameter("company", company).setParameter("ids", evidence).getResultList());
        return roots.stream().map(root -> {
            var version = byRoot.get(root.id);
            var verification = verifications.get(version.id);
            var application = applications.get(version.id);
            String cause = null;
            Result result = Result.SIN_VERIFICAR;
            if (verification != null) {
                if (blocked.contains(verification.evidence) || blocked.contains(application.evidence)) {
                    cause = "EVIDENCIA_BLOQUEADA";
                } else if (!verification.nextReview.isAfter(today)) {
                    cause = "VENCIDA";
                } else {
                    result = verification.result();
                }
            }
            return new Implementation(root.id, root.catalog, root.element, root.state, root.revision,
                    versionView(version, applications), result, verification == null ? null : verification.id, cause);
        }).toList();
    }

    private Map<UUID, ControlApplication> applications(UUID company, List<ControlVersion> versions) {
        if (versions.isEmpty()) return Map.of();
        var ids = versions.stream().map(version -> version.id).toList();
        return entities.createQuery("""
                select a from ControlApplication a where a.company = :company and a.version in :ids
                """, ControlApplication.class).setParameter("company", company).setParameter("ids", ids)
                .getResultList().stream().collect(Collectors.toMap(application -> application.version, Function.identity()));
    }

    private Map<UUID, ControlVerification> latest(UUID company, List<ControlVersion> versions) {
        var ids = versions.stream().map(version -> version.id).toList();
        if (ids.isEmpty()) return Map.of();
        var rows = entities.createQuery("""
                select v from ControlVerification v where v.company = :company and v.version in :ids
                and v.revision = (select max(other.revision) from ControlVerification other where other.version = v.version)
                """, ControlVerification.class).setParameter("company", company).setParameter("ids", ids).getResultList();
        var result = new HashMap<UUID, ControlVerification>();
        rows.forEach(row -> result.put(row.version, row));
        return result;
    }

    private static Version versionView(ControlVersion version, Map<UUID, ControlApplication> applications) {
        var applied = applications.get(version.id);
        return version.view(applied == null ? null : applied.view());
    }

    private void requireCatalog(UUID company, UUID catalog, UUID element) {
        try {
            if (catalogs.element(company, catalog, element).tipo() == ReferenceCatalogs.Type.CONTROL
                    && catalogs.catalog(company, catalog).disponibleNuevos()) return;
        } catch (ReferenceNotFound missing) {
            throw new ControlFailure(CATALOG);
        }
        throw new ControlFailure(CATALOG);
    }

    private void requireResponsible(UUID company, UUID id) {
        var rows = entities.createNativeQuery("""
                select id from responsables_negocio where empresa_id = :company and id = :id and estado = 'ACTIVO' for share
                """, UUID.class).setParameter("company", company).setParameter("id", id).getResultList();
        if (rows.isEmpty()) throw new ControlFailure(RESPONSIBLE);
    }

    private void advance(Scope scope, ImplementationRecord root, State state, String action, String reason) {
        String previous = root.state.name();
        root.state = state;
        root.revision++;
        record(scope, root.id, "IMPLEMENTACION_CONTROL", action, previous, state.name(), reason);
    }

    private void record(Scope scope, UUID id, String type, String action, String from, String to, String reason) {
        entities.flush();
        audit.append(new ActivityLog.Event(scope.company(), scope.actor(), action, type, id, from, to, reason, scope.correlation()));
    }

    private ZoneId zone(Scope scope, CompanyPermission permission) {
        return ZoneId.of(access.require(scope.company(), permission).zonaHoraria());
    }

    private <T> T read(Scope scope, Supplier<T> task) {
        return work.execute(scope.actor(), scope.company(), CompanyPermission.READ_CONTROLS, task);
    }

    private <T> T write(Scope scope, Supplier<T> task) {
        try {
            return work.execute(scope.actor(), scope.company(), CompanyPermission.EDIT_CONTROLS, task);
        } catch (JDBCException failure) {
            if ("P1901".equals(failure.getSQLState())) throw new ControlFailure(EVIDENCE);
            if ("P1902".equals(failure.getSQLState())) throw new ControlFailure(STATE);
            if (failure instanceof ConstraintViolationException constraint
                    && "verificacion_control_evidencia_nueva".equals(constraint.getConstraintName())) {
                throw new ControlFailure(NEW_EVIDENCE);
            }
            throw failure;
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
