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
import software.mspi.empresas.api.CompanyAccess;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;
import software.mspi.evaluaciones.api.EvaluationDrafts;
import software.mspi.evidencias.api.EvidenceFailure;
import software.mspi.evidencias.api.ResponseEvidence;

import static software.mspi.evidencias.api.EvidenceFailure.Reason.*;

@Service
@ConditionalOnProperty(name = {"mspi.tenancy.enabled", "mspi.evidence.enabled"}, havingValue = "true")
@EnableConfigurationProperties(EvidenceSettings.class)
class JpaResponseEvidence implements ResponseEvidence {
    private final TenantUnitOfWork work;
    private final CompanyAccess access;
    private final EvaluationDrafts evaluations;
    private final ActivityLog audit;
    private final EvidenceSettings settings;
    private final PrivateEvidenceFiles files;
    private final EvidenceAntimalware antimalware;
    private final SecurityIncidents incidents;

    @PersistenceContext(unitName = "enterprise")
    private EntityManager entities;

    JpaResponseEvidence(TenantUnitOfWork work, CompanyAccess access, EvaluationDrafts evaluations, ActivityLog audit,
                        EvidenceSettings settings, EvidenceAntimalware antimalware, SecurityIncidents incidents)
            throws IOException {
        this.work = work;
        this.access = access;
        this.evaluations = evaluations;
        this.audit = audit;
        this.settings = settings;
        this.files = new PrivateEvidenceFiles(settings.directory());
        this.antimalware = antimalware;
        this.incidents = incidents;
    }

    @Override
    public Metadata upload(Target target, long revision, String name, String description, InputStream input) {
        // Check authorization and draft revision before scanning; recheck at publication.
        evaluations.attachEvidence(scope(target), target.evaluation(), target.response(), revision, Optional::empty);
        Long quota = settings.quotas().get(target.company());
        if (quota == null) throw new EvidenceFailure(QUOTA_NOT_CONFIGURED);
        try (var lease = antimalware.reserve()) {
            byte[] bytes = EvidenceContent.read(name, input);
            var inspection = lease.inspect(bytes);
            if (!clean(target, inspection)) throw new EvidenceFailure(MALWARE);
            var content = EvidenceContent.inspect(name, bytes);
            return evaluations.attachEvidence(scope(target), target.evaluation(), target.response(), revision,
                    () -> publish(target, name, description, content, inspection, quota))
                    .orElseThrow(() -> new EvidenceFailure(STORAGE));
        }
    }

    private Optional<Metadata> publish(Target target, String name, String description, EvidenceContent content,
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
        access.require(target.company(), CompanyPermission.EDIT_EVALUATION_DRAFT);
        record.state = "DISPONIBLE";
        recordOutcome(target, record, null);
        return Optional.of(record.metadata());
    }

    @Override
    public Page list(Target target, int page, int size) {
        return work.execute(target.actor(), target.company(), CompanyPermission.READ_EVALUATIONS, () -> {
            evaluations.requireReadableResponse(scope(target), target.evaluation(), target.response());
            long offset = (long) page * size;
            if (offset > Integer.MAX_VALUE) return new Page(List.of(), page, size, false);
            var rows = entities.createQuery("""
                    select e from EvidenceRecord e
                    where e.company = :company and e.evaluation = :evaluation and e.response = :response
                        and e.state = 'DISPONIBLE' and e.inspection <> 'BLOQUEADA'
                    order by e.created, e.id
                    """, EvidenceRecord.class).setParameter("company", target.company())
                    .setParameter("evaluation", target.evaluation()).setParameter("response", target.response())
                    .setFirstResult((int) offset).setMaxResults(size + 1).getResultList();
            return new Page(rows.stream().limit(size).map(EvidenceRecord::metadata).toList(), page, size, rows.size() > size);
        });
    }

    @Override
    public Download download(Target target, UUID evidence) {
        var record = work.execute(target.actor(), target.company(), CompanyPermission.READ_EVALUATIONS, () -> {
            evaluations.requireReadableResponse(scope(target), target.evaluation(), target.response());
            return available(target, evidence, LockModeType.NONE).orElseThrow(() -> new EvidenceFailure(MISSING));
        });
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

    private Metadata recordInspection(Target target, UUID evidence, EvidenceAntimalware.Result inspection) {
        return work.execute(target.actor(), target.company(), CompanyPermission.READ_EVALUATIONS, () -> {
            evaluations.requireReadableResponse(scope(target), target.evaluation(), target.response());
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
    public Optional<String> verifiedHash(Target target, UUID evidence) {
        return work.execute(target.actor(), target.company(), CompanyPermission.EDIT_EVALUATION_DRAFT, () ->
                available(target, evidence, LockModeType.NONE).flatMap(record -> {
                    try (var lease = antimalware.reserve()) {
                        return clean(target, lease.inspect(files.read(record))) ? Optional.of(record.digest) : Optional.empty();
                    } catch (IOException failure) {
                        return Optional.empty();
                    }
                }));
    }

    private boolean clean(Target target, EvidenceAntimalware.Result inspection) {
        if (inspection.clean()) return true;
        incidents.record(SecurityIncidents.Type.AMENAZA_EVIDENCIA, target.correlation());
        return false;
    }

    private Optional<EvidenceRecord> available(Target target, UUID evidence, LockModeType lock) {
        return Optional.ofNullable(entities.find(EvidenceRecord.class, evidence, lock))
                .filter(record -> target.company().equals(record.company) && target.evaluation().equals(record.evaluation)
                        && target.response().equals(record.response) && "DISPONIBLE".equals(record.state)
                        && !"BLOQUEADA".equals(record.inspection));
    }

    private int changeUsage(UUID company, int delta, long maximumCurrentUsage) {
        return entities.createNativeQuery("""
                UPDATE uso_evidencias SET bytes_ocupados = bytes_ocupados + :delta
                WHERE empresa_id = :company AND bytes_ocupados <= :maximum
                """).setParameter("delta", delta).setParameter("company", company)
                .setParameter("maximum", maximumCurrentUsage).executeUpdate();
    }

    private void recordOutcome(Target target, EvidenceRecord record, String reason) {
        entities.flush();
        audit.append(new ActivityLog.Event(record.company, target.actor(), "CARGAR_EVIDENCIA", "EVIDENCIA",
                record.id, "PENDIENTE", record.state, reason, target.correlation()));
    }

    private static EvaluationDrafts.Scope scope(Target target) {
        return new EvaluationDrafts.Scope(target.actor(), target.company(), target.correlation());
    }
}
