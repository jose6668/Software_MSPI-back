package software.mspi.evaluaciones.internal.persistence.tenant;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import software.mspi.evaluaciones.api.EvaluationDrafts.Answer;
import software.mspi.evaluaciones.api.EvaluationDrafts.Applicability;
import software.mspi.evaluaciones.api.EvaluationDrafts.ElementType;
import software.mspi.evaluaciones.api.EvaluationDrafts.Response;
import software.mspi.evaluaciones.api.EvaluationFailure;

@Entity
@Table(name = "respuestas_evaluacion")
class EvaluationResponse {

    private static final Set<Integer> SCORES = Set.of(0, 20, 40, 60, 80, 100);

    @Id UUID id;
    @Column(name = "empresa_id", updatable = false) UUID company;
    @Column(name = "evaluacion_id", updatable = false) UUID evaluation;
    @Column(name = "version_catalogo_id", updatable = false) UUID version;
    @Column(name = "elemento_id", updatable = false) UUID element;
    @Column(name = "tipo", updatable = false) @Enumerated(EnumType.STRING) ElementType type;
    @Column(name = "codigo", updatable = false) String code;
    @Column(name = "titulo", updatable = false) String title;
    @Column(name = "grupo", updatable = false) String groupCode;
    @Column(name = "aplicabilidad") @Enumerated(EnumType.STRING) Applicability applicability;
    @Column(name = "justificacion") String justification;
    @Column(name = "calificacion") Integer score;
    @Column(name = "fundamento") String rationale;
    @Column(name = "actualizada_por") UUID updatedBy;
    @Column(name = "actualizada_en") Instant updatedAt;
    @Column(name = "respuesta_anterior", insertable = false, updatable = false) UUID previousResponse;
    @Column(name = "heredada_confirmada", insertable = false) boolean inheritedConfirmed;

    protected EvaluationResponse() {}

    void update(Answer answer, UUID actor, Instant now) {
        var invalid = new ArrayList<String>();
        boolean excluded = answer.aplicabilidad() == Applicability.NO_APLICA;
        if (excluded && type == ElementType.CLAUSULA) invalid.add("aplicabilidad");
        if (excluded != (answer.justificacionAplicabilidad() != null)) invalid.add("justificacionAplicabilidad");
        if (answer.calificacion() != null) {
            if (answer.aplicabilidad() != Applicability.APLICA || !SCORES.contains(answer.calificacion())) {
                invalid.add("calificacion");
            }
            if (answer.fundamento() == null) invalid.add("fundamento");
        }
        if (!invalid.isEmpty()) {
            throw new EvaluationFailure(EvaluationFailure.Reason.INVALID_ANSWER, invalid);
        }

        applicability = answer.aplicabilidad();
        justification = answer.justificacionAplicabilidad();
        score = answer.calificacion();
        rationale = answer.fundamento();
        confirm(actor, now);
    }

    void confirm(UUID actor, Instant now) {
        inheritedConfirmed = true;
        updatedBy = actor;
        updatedAt = now;
    }

    Response view(long revision) {
        return new Response(id, element, version, type, code, title, groupCode,
                new Answer(applicability, justification, score, rationale), revision, updatedBy, updatedAt,
                previousResponse, inheritedConfirmed);
    }
}
