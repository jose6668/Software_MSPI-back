package software.mspi.evaluaciones.internal.persistence.tenant;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import software.mspi.evaluaciones.api.EvaluationDrafts.Finding;
import software.mspi.evaluaciones.api.EvaluationDrafts.Gap;
import software.mspi.evaluaciones.api.EvaluationDrafts.Scope;

@Entity
@Table(name = "hallazgos_evaluacion")
class EvaluationFinding {

    @Id UUID id;
    @Column(name = "empresa_id", updatable = false) UUID company;
    @Column(name = "evaluacion_id", updatable = false) UUID evaluation;
    @Column(name = "respuesta_id", updatable = false) UUID response;
    @Column(name = "descripcion") String description;
    @Column(name = "recomendacion") String recommendation;
    @Column(name = "creada_por", updatable = false) UUID createdBy;
    @Column(name = "creada_en", updatable = false) Instant createdAt;
    @Column(name = "actualizada_por") UUID updatedBy;
    @Column(name = "actualizada_en") Instant updatedAt;

    protected EvaluationFinding() {}

    EvaluationFinding(Scope scope, UUID evaluation, UUID response, Instant now) {
        id = UUID.randomUUID();
        company = scope.company();
        this.evaluation = evaluation;
        this.response = response;
        createdBy = scope.actor();
        createdAt = now;
    }

    void update(Gap gap, UUID actor, Instant now) {
        description = gap.descripcionBrecha();
        recommendation = gap.recomendacion();
        updatedBy = actor;
        updatedAt = now;
    }

    Finding view(long revision) {
        return new Finding(id, response, new Gap(description, recommendation), revision, createdBy, createdAt, updatedBy, updatedAt);
    }
}
