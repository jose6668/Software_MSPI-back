package software.mspi.evidencias.internal.persistence.tenant;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import software.mspi.auditoria.api.ActivityLog;
import software.mspi.auditoria.api.SecurityIncidents;
import software.mspi.acciones.api.ImprovementActions;
import software.mspi.controles.api.ControlImplementations;
import software.mspi.empresas.api.CompanyAccess;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;
import software.mspi.evaluaciones.api.EvaluationDrafts;
import software.mspi.evidencias.api.EvidenceFailure;
import software.mspi.evidencias.api.ActionEvidence;
import software.mspi.evidencias.api.ControlEvidence;
import software.mspi.evidencias.api.ResponseEvidence;
import software.mspi.evidencias.api.ResponseEvidence.Download;
import software.mspi.evidencias.api.ResponseEvidence.Metadata;
import software.mspi.evidencias.api.ResponseEvidence.Page;
import java.util.function.Supplier;

import static software.mspi.evidencias.api.EvidenceFailure.Reason.*;

@Service
@ConditionalOnProperty(name = {"mspi.tenancy.enabled", "mspi.evidence.enabled"}, havingValue = "true")
@EnableConfigurationProperties(EvidenceSettings.class)
class JpaResponseEvidence implements ResponseEvidence, ControlEvidence, ActionEvidence {
    private final TenantUnitOfWork work;
    private final CompanyAccess access;
    private final EvaluationDrafts evaluations;
    private final ControlImplementations controls;
    private final ImprovementActions actions;
    private final ActivityLog audit;
    private final EvidenceSettings settings;
    private final PrivateEvidenceFiles files;
    private final EvidenceAntimalware antimalware;
    private final SecurityIncidents incidents;

    @PersistenceContext(unitName = "enterprise")
    private EntityManager entities;

    JpaResponseEvidence(TenantUnitOfWork work, CompanyAccess access, EvaluationDrafts evaluations, ControlImplementations controls,
                        ImprovementActions actions, ActivityLog audit,
                        EvidenceSettings settings, EvidenceAntimalware antimalware, SecurityIncidents incidents)
            throws IOException {
        this.work = work;
        this.access = access;
        this.evaluations = evaluations;
        this.controls = controls;
        this.actions = actions;
        this.audit = audit;
        this.settings = settings;
        this.files = new PrivateEvidenceFiles(settings.directory());
        this.antimalware = antimalware;
        this.incidents = incidents;
    }

    @Override
    public Metadata upload(ResponseEvidence.Target target, long revision, String name, String description, InputStream input) {
        return upload(StoreTarget.response(target), revision, name, description, input);
    }

    @Override
    public Metadata upload(ControlEvidence.Target target, long revision, String name, String description, InputStream input) {
        return upload(StoreTarget.control(target), revision, name, description, input);
    }

    private Metadata upload(StoreTarget target, long revision, String name, String description, InputStream input) {
        // Check authorization and revision before scanning; recheck at publication.
        attach(target, revision, Optional::empty);
        Long quota = settings.quotas().get(target.company());
        if (quota == null) throw new EvidenceFailure(QUOTA_NOT_CONFIGURED);
        try (var lease = antimalware.reserve()) {
            byte[] bytes = EvidenceContent.read(name, input);
            var inspection = lease.inspect(bytes);
            if (!clean(target, inspection)) throw new EvidenceFailure(MALWARE);
            var content = EvidenceContent.inspect(name, bytes);
            return attach(target, revision,
                    () -> publish(target, name, description, content, inspection, quota))
                    .orElseThrow(() -> new EvidenceFailure(STORAGE));
        }
    }

    private <T> Optional<T> attach(StoreTarget target, long revision, Supplier<Optional<T>> publish) {
        if (target.action() != null) {
            return actions.attachEvidence(actionScope(target), target.action(), revision, publish);
        }
        if (target.implementation() != null) {
            return controls.attachEvidence(controlScope(target), target.implementation(), target.version(), revision, publish);
        }
        return evaluations.attachEvidence(scope(target), target.evaluation(), target.response(), revision, publish);
    }

    private Optional<Metadata> publish(StoreTarget target, String name, String description, EvidenceContent content,
                                      EvidenceAntimalware.Result inspection, long quota) {
        var record = new EvidenceRecord(target, name, description, content);
        record.inspection = "LIMPIA";
        record.inspectedAt = inspection.at();
        record.engine = inspection.engine();
        long remaining = quota - record.size;
        if (remaining < 0 || changeUsage(record.company, record.size, remaining) != 1) {
            throw new EvidenceFailure(QUOTA);
        }
        entities.persist(record);
        entities.flush();
        try {
            files.store(record.company, record.id, content.bytes());
        } catch (IOException failure) {
            changeUsage(record.company, -record.size, Long.MAX_VALUE);
            record.state = "FALLIDA";
            recordOutcome(target, record, "ALMACENAMIENTO");
            return Optional.empty();
        }
        access.require(target.company(), target.editPermission());
        record.state = "DISPONIBLE";
        recordOutcome(target, record, null);
        return Optional.of(record.metadata());
    }

    @Override
    public Page list(ResponseEvidence.Target target, int page, int size) {
        return list(StoreTarget.response(target), page, size);
    }

    @Override
    public Page list(ControlEvidence.Target target, int page, int size) {
        return list(StoreTarget.control(target), page, size);
    }

    private Page list(StoreTarget target, int page, int size) {
        if (target.action() != null) {
            return actions.readEvidence(actionScope(target), target.action(), () -> listAuthorized(target, page, size));
        }
        return work.execute(target.actor(), target.company(), target.readPermission(), () -> {
            requireReadable(target);
            return listAuthorized(target, page, size);
        });
    }

    private Page listAuthorized(StoreTarget target, int page, int size) {
            long offset = (long) page * size;
            if (offset > Integer.MAX_VALUE) return new Page(List.of(), page, size, false);
            boolean control = target.implementation() != null;
            String destination = target.action() != null ? "e.action = :parent"
                    : control ? "e.implementation = :parent and e.version = :destination"
                    : "e.evaluation = :parent and e.response = :destination";
            var rows = entities.createQuery(("""
                    select e from EvidenceRecord e where e.company = :company
                        and %s and e.state = 'DISPONIBLE' and e.inspection <> 'BLOQUEADA'
                    order by e.created, e.id
                    """).formatted(destination), EvidenceRecord.class).setParameter("company", target.company())
                    .setParameter("parent", target.action() != null ? target.action()
                            : control ? target.implementation() : target.evaluation());
            if (target.action() == null) rows.setParameter("destination", control ? target.version() : target.response());
            var results = rows.setFirstResult((int) offset).setMaxResults(size + 1).getResultList();
            return new Page(results.stream().limit(size).map(EvidenceRecord::metadata).toList(), page, size, results.size() > size);
    }

    @Override
    public Metadata upload(ImprovementActions.Scope scope, UUID action, long revision, String name, String description, InputStream input) {
        return upload(StoreTarget.action(scope, action), revision, name, description, input);
    }

    @Override
    public Page list(ImprovementActions.Scope scope, UUID action, int page, int size) {
        return list(StoreTarget.action(scope, action), page, size);
    }

    @Override
    public Download download(ImprovementActions.Scope scope, UUID action, UUID evidence) {
        return download(StoreTarget.action(scope, action), evidence);
    }

    @Override
    public boolean verify(ImprovementActions.Scope scope, UUID action, UUID evidence) {
        var target = StoreTarget.action(scope, action);
        var record = available(target, evidence, LockModeType.PESSIMISTIC_READ);
        if (record.isEmpty() || !"LIMPIA".equals(record.get().inspection)) return false;
        try (var lease = antimalware.reserve()) {
            return clean(target, lease.inspect(files.read(record.get())));
        } catch (IOException failure) {
            return false;
        }
    }

    @Override
    public Download download(ResponseEvidence.Target target, UUID evidence) {
        return download(StoreTarget.response(target), evidence);
    }

    @Override
    public Download download(ControlEvidence.Target target, UUID evidence) {
        return download(StoreTarget.control(target), evidence);
    }

    @Override
    public Download downloadActionContext(ResponseEvidence.ActionContextTarget target) {
        actions.requireContextEvidence(new ImprovementActions.Scope(target.actor(), target.company(), target.correlation()),
                target.action(), target.evidence());
        var record = work.execute(target.actor(), target.company(), CompanyPermission.READ_ASSIGNED_ACTIONS,
                () -> entities.find(EvidenceRecord.class, target.evidence()));
        if (record == null || !target.company().equals(record.company) || !"DISPONIBLE".equals(record.state)
                || !"LIMPIA".equals(record.inspection)) throw new EvidenceFailure(MISSING);
        try {
            byte[] content = files.read(record);
            try (var lease = antimalware.reserve()) {
                var inspection = lease.inspect(content);
                if (!inspection.clean()) {
                    incidents.record(SecurityIncidents.Type.AMENAZA_EVIDENCIA, target.correlation());
                    work.execute(target.actor(), target.company(), CompanyPermission.READ_ASSIGNED_ACTIONS, () -> {
                        var current = entities.find(EvidenceRecord.class, target.evidence(), LockModeType.PESSIMISTIC_WRITE);
                        if (current != null && target.company().equals(current.company)) {
                            String previous = current.inspection;
                            current.inspection = "BLOQUEADA";
                            current.inspectedAt = inspection.at();
                            current.engine = inspection.engine();
                            entities.flush();
                            audit.append(new ActivityLog.Event(target.company(), target.actor(), "INSPECCIONAR_EVIDENCIA",
                                    "EVIDENCIA", current.id, previous, "BLOQUEADA", null, target.correlation()));
                        }
                        return null;
                    });
                    throw new EvidenceFailure(MALWARE);
                }
            }
            return new Download(record.metadata(), content);
        } catch (IOException failure) {
            throw new EvidenceFailure(STORAGE);
        }
    }

    private Download download(StoreTarget target, UUID evidence) {
        var record = withReadable(target, () ->
                available(target, evidence, LockModeType.NONE).orElseThrow(() -> new EvidenceFailure(MISSING)));
        try (var lease = antimalware.reserve()) {
            byte[] content = files.read(record);
            EvidenceAntimalware.Result inspection;
            try {
                inspection = lease.inspect(content);
            } catch (EvidenceFailure unavailable) {
                recordInspection(target, evidence, null);
                throw unavailable;
            }
            boolean clean = clean(target, inspection);
            var metadata = recordInspection(target, evidence, inspection);
            if (!clean) throw new EvidenceFailure(MALWARE);
            return new Download(metadata, content);
        } catch (IOException failure) {
            throw new EvidenceFailure(STORAGE);
        }
    }

    private Metadata recordInspection(StoreTarget target, UUID evidence, EvidenceAntimalware.Result inspection) {
        return withReadable(target, () -> {
            var record = available(target, evidence, LockModeType.PESSIMISTIC_WRITE)
                    .orElseThrow(() -> new EvidenceFailure(MISSING));
            String previous = record.inspection;
            String next = inspection == null ? "ERROR" : inspection.clean() ? "LIMPIA" : "BLOQUEADA";
            String engine = inspection == null ? null : inspection.engine();
            if (!next.equals(previous) || !Objects.equals(engine, record.engine)) {
                record.inspection = next;
                record.inspectedAt = inspection == null ? null : inspection.at();
                record.engine = engine;
                entities.flush();
                audit.append(new ActivityLog.Event(record.company, target.actor(), "INSPECCIONAR_EVIDENCIA", "EVIDENCIA",
                        record.id, previous, next, null, target.correlation()));
            }
            return record.metadata();
        });
    }

    @Override
    public Optional<String> verifiedHash(ResponseEvidence.Target response, UUID evidence) {
        var target = StoreTarget.response(response);
        return work.execute(target.actor(), target.company(), CompanyPermission.EDIT_EVALUATION_DRAFT, () ->
                available(target, evidence, LockModeType.NONE).flatMap(record -> {
                    try (var lease = antimalware.reserve()) {
                        return clean(target, lease.inspect(files.read(record))) ? Optional.of(record.digest) : Optional.empty();
                    } catch (IOException failure) {
                        return Optional.empty();
                    }
                }));
    }

    @Override
    public Optional<String> verifiedHash(ControlImplementations.Scope scope, UUID implementation, UUID version, UUID evidence) {
        var target = StoreTarget.control(new ControlEvidence.Target(scope, implementation, version));
        var record = work.execute(target.actor(), target.company(), target.editPermission(),
                () -> available(target, evidence, LockModeType.NONE));
        if (record.isEmpty()) return Optional.empty();
        try (var lease = antimalware.reserve()) {
            var attachment = record.get();
            var inspection = lease.inspect(files.read(attachment));
            boolean clean = clean(target, inspection);
            recordInspection(target, evidence, inspection);
            return clean ? Optional.of(attachment.digest) : Optional.empty();
        } catch (IOException failure) {
            return Optional.empty();
        } catch (EvidenceFailure failure) {
            if (failure.reason() == MISSING) return Optional.empty();
            throw failure;
        }
    }

    private void requireReadable(StoreTarget target) {
        if (target.implementation() != null) {
            controls.requireVersion(controlScope(target), target.implementation(), target.version());
        } else {
            evaluations.requireReadableResponse(scope(target), target.evaluation(), target.response());
        }
    }

    private <T> T withReadable(StoreTarget target, Supplier<T> operation) {
        if (target.action() != null) return actions.readEvidence(actionScope(target), target.action(), operation);
        return work.execute(target.actor(), target.company(), target.readPermission(), () -> {
            requireReadable(target);
            return operation.get();
        });
    }

    private boolean clean(StoreTarget target, EvidenceAntimalware.Result inspection) {
        if (inspection.clean()) return true;
        incidents.record(SecurityIncidents.Type.AMENAZA_EVIDENCIA, target.correlation());
        return false;
    }

    private Optional<EvidenceRecord> available(StoreTarget target, UUID evidence, LockModeType lock) {
        return Optional.ofNullable(entities.find(EvidenceRecord.class, evidence, lock))
                .filter(record -> target.company().equals(record.company) && Objects.equals(target.evaluation(), record.evaluation)
                        && Objects.equals(target.response(), record.response) && Objects.equals(target.implementation(), record.implementation)
                        && Objects.equals(target.version(), record.version) && Objects.equals(target.action(), record.action)
                        && "DISPONIBLE".equals(record.state)
                        && !"BLOQUEADA".equals(record.inspection));
    }

    private int changeUsage(UUID company, int delta, long maximumCurrentUsage) {
        return entities.createNativeQuery("""
                UPDATE uso_evidencias SET bytes_ocupados = bytes_ocupados + :delta
                WHERE empresa_id = :company AND bytes_ocupados <= :maximum
                """).setParameter("delta", delta).setParameter("company", company)
                .setParameter("maximum", maximumCurrentUsage).executeUpdate();
    }

    private void recordOutcome(StoreTarget target, EvidenceRecord record, String reason) {
        entities.flush();
        audit.append(new ActivityLog.Event(record.company, target.actor(), "CARGAR_EVIDENCIA", "EVIDENCIA",
                record.id, "PENDIENTE", record.state, reason, target.correlation()));
    }

    private static EvaluationDrafts.Scope scope(StoreTarget target) {
        return new EvaluationDrafts.Scope(target.actor(), target.company(), target.correlation());
    }

    private static ControlImplementations.Scope controlScope(StoreTarget target) {
        return new ControlImplementations.Scope(target.actor(), target.company(), target.correlation());
    }

    private static ImprovementActions.Scope actionScope(StoreTarget target) {
        return new ImprovementActions.Scope(target.actor(), target.company(), target.correlation());
    }

    record StoreTarget(UUID actor, UUID company, UUID correlation, UUID evaluation, UUID response,
                       UUID implementation, UUID version, UUID action) {
        static StoreTarget action(ImprovementActions.Scope scope, UUID action) {
            return new StoreTarget(scope.actor(), scope.company(), scope.correlation(), null, null, null, null, action);
        }
        static StoreTarget response(ResponseEvidence.Target target) {
            return new StoreTarget(target.actor(), target.company(), target.correlation(), target.evaluation(), target.response(),
                    null, null, null);
        }

        static StoreTarget control(ControlEvidence.Target target) {
            var scope = target.scope();
            return new StoreTarget(scope.actor(), scope.company(), scope.correlation(), null, null,
                    target.implementation(), target.version(), null);
        }

        CompanyPermission readPermission() {
            return implementation == null ? CompanyPermission.READ_EVALUATIONS : CompanyPermission.READ_CONTROLS;
        }

        CompanyPermission editPermission() {
            if (action != null) return CompanyPermission.EXECUTE_ACTION;
            return implementation == null ? CompanyPermission.EDIT_EVALUATION_DRAFT : CompanyPermission.EDIT_CONTROLS;
        }
    }
}
