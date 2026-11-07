package software.mspi.evidencias.internal.persistence.tenant;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import software.mspi.auditoria.api.ActivityLog;
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

    @PersistenceContext(unitName = "enterprise")
    private EntityManager entities;

    JpaResponseEvidence(TenantUnitOfWork work, CompanyAccess access, EvaluationDrafts evaluations, ActivityLog audit,
                        EvidenceSettings settings) throws IOException {
        this.work = work;
        this.access = access;
        this.evaluations = evaluations;
        this.audit = audit;
        this.settings = settings;
        this.files = new PrivateEvidenceFiles(settings.directory());
    }

    @Override
    public Metadata upload(Target target, long revision, String name, String description, InputStream input) {
        return evaluations.attachEvidence(scope(target), target.evaluation(), target.response(), revision,
                () -> publish(target, name, description, input)).orElseThrow(() -> new EvidenceFailure(STORAGE));
    }

    private Optional<Metadata> publish(Target target, String name, String description, InputStream input) {
        Long quota = settings.quotas().get(target.company());
        if (quota == null) throw new EvidenceFailure(QUOTA_NOT_CONFIGURED);

        var content = EvidenceContent.inspect(name, input);
        var record = new EvidenceRecord(target, name, description, content);
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
                        and e.state = 'DISPONIBLE'
                    order by e.created, e.id
                    """, EvidenceRecord.class).setParameter("company", target.company())
                    .setParameter("evaluation", target.evaluation()).setParameter("response", target.response())
                    .setFirstResult((int) offset).setMaxResults(size + 1).getResultList();
            return new Page(rows.stream().limit(size).map(EvidenceRecord::metadata).toList(), page, size, rows.size() > size);
        });
    }

    @Override
    public Download download(Target target, UUID evidence) {
        return work.execute(target.actor(), target.company(), CompanyPermission.READ_EVALUATIONS, () -> {
            evaluations.requireReadableResponse(scope(target), target.evaluation(), target.response());
            var record = available(target, evidence).orElseThrow(() -> new EvidenceFailure(MISSING));
            try {
                return new Download(record.metadata(), files.read(record));
            } catch (IOException failure) {
                throw new EvidenceFailure(STORAGE);
            }
        });
    }

    @Override
    public Optional<String> verifiedHash(Target target, UUID evidence) {
        return work.execute(target.actor(), target.company(), CompanyPermission.EDIT_EVALUATION_DRAFT, () ->
                available(target, evidence).filter(files::isIntact).map(record -> record.digest));
    }

    private Optional<EvidenceRecord> available(Target target, UUID evidence) {
        return Optional.ofNullable(entities.find(EvidenceRecord.class, evidence))
                .filter(record -> target.company().equals(record.company) && target.evaluation().equals(record.evaluation)
                        && target.response().equals(record.response) && "DISPONIBLE".equals(record.state));
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
