package software.mspi.evaluaciones.internal.persistence.tenant;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.springframework.dao.OptimisticLockingFailureException;
import software.mspi.evaluaciones.api.EvaluationDrafts.Create;
import software.mspi.evaluaciones.api.EvaluationDrafts.Evaluation;
import software.mspi.evaluaciones.api.EvaluationDrafts.Scope;
import software.mspi.evaluaciones.api.EvaluationFailure;

@Entity
@Table(name = "evaluaciones")
class EvaluationRecord {

    @Id UUID id;
    @Column(name = "empresa_id", updatable = false) UUID company;
    @Column(name = "nombre", updatable = false) String name;
    @Column(name = "fecha_evaluacion", updatable = false) LocalDate date;
    @Column(name = "alcance", updatable = false) String scope;
    @Column(name = "responsable_id", updatable = false) UUID responsible;
    @Column(name = "version_catalogo_id", updatable = false) UUID catalogVersion;
    @Column(name = "version_metodologia_id", updatable = false) UUID methodologyVersion;
    @Column(name = "estado", insertable = false, updatable = false) String state;
    @Version @Column(name = "revision") long revision;
    @Column(name = "creada_por", updatable = false) UUID createdBy;
    @Column(name = "creada_en", updatable = false) Instant createdAt;
    @Column(name = "actualizada_en") Instant updatedAt;

    protected EvaluationRecord() {}

    EvaluationRecord(Scope context, Create input, Instant now) {
        id = UUID.randomUUID();
        company = context.company();
        name = input.nombre();
        date = input.fechaEvaluacion();
        scope = input.alcance();
        responsible = input.responsableId();
        catalogVersion = input.versionCatalogoId();
        methodologyVersion = input.versionMetodologiaId();
        state = "BORRADOR";
        createdBy = context.actor();
        createdAt = now;
        updatedAt = now;
    }

    void requireEditable(long expected) {
        if (!"BORRADOR".equals(state)) {
            throw new EvaluationFailure(EvaluationFailure.Reason.NOT_EDITABLE);
        }
        if (revision != expected) {
            throw new OptimisticLockingFailureException("Revisión desactualizada.");
        }
    }

    void touch(Instant now) {
        // PostgreSQL stores timestamps at microsecond precision.
        Instant next = updatedAt.plusNanos(1000);
        updatedAt = now.isAfter(next) ? now : next;
    }

    Evaluation view() {
        return new Evaluation(id, name, date, scope, responsible, catalogVersion, methodologyVersion, state, revision,
                createdBy, createdAt, updatedAt);
    }
}
