package software.mspi.acciones.internal.persistence.tenant;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "acciones_mejora")
class ImprovementActionRecord {
    @Id UUID id;
    @Column(name = "empresa_id", updatable = false) UUID company;
    @Column(name = "codigo", updatable = false) String code;
    @Column(name = "riesgo_id") UUID risk;
    @Column(name = "hallazgo_id") UUID finding;
    @Column(name = "descripcion") String description;
    @Column(name = "resultado_esperado") String expectedResult;
    @Column(name = "responsable_membresia_id") UUID assignee;
    @Column(name = "fecha_limite") LocalDate dueDate;
    @Column(name = "evidencia_requerida") boolean evidenceRequired;
    @Column(name = "descripcion_evidencia") String evidenceDescription;
    @Column(name = "justificacion_sin_evidencia") String noEvidenceReason;
    @Column(name = "resumen_contexto_ra") String contextSummary;
    @Column(name = "estado") String state;
    @Column(name = "motivo_cancelacion") String cancellationReason;
    @Column(name = "accion_anterior_id", updatable = false) UUID previous;
    @Column(name = "revision") long revision;
    @Column(name = "motivo_cambio") String changeReason;
    @Column(name = "creada_por", updatable = false) UUID author;
    @Column(name = "creada_en", updatable = false) Instant created;

    protected ImprovementActionRecord() {}

    ImprovementActionRecord(UUID id, UUID company, String code, DraftData draft, UUID actor, Instant now) {
        this.id = id;
        this.company = company;
        this.code = code;
        this.risk = draft.risk();
        this.finding = draft.finding();
        this.description = draft.description();
        this.expectedResult = draft.expectedResult();
        this.assignee = draft.assigneeMembership();
        this.dueDate = draft.dueDate();
        this.evidenceRequired = draft.evidenceRequired();
        this.evidenceDescription = draft.evidenceDescription();
        this.noEvidenceReason = draft.noEvidenceReason();
        this.contextSummary = draft.contextSummary();
        this.state = "PENDIENTE";
        this.revision = 0;
        this.author = actor;
        this.created = now;
    }

    void update(DraftData draft, String reason) {
        risk = draft.risk();
        finding = draft.finding();
        description = draft.description();
        expectedResult = draft.expectedResult();
        assignee = draft.assigneeMembership();
        dueDate = draft.dueDate();
        evidenceRequired = draft.evidenceRequired();
        evidenceDescription = draft.evidenceDescription();
        noEvidenceReason = draft.noEvidenceReason();
        contextSummary = draft.contextSummary();
        changeReason = reason;
        revision++;
    }

    record DraftData(UUID risk, UUID finding, String description, String expectedResult, UUID assigneeMembership,
                     LocalDate dueDate, boolean evidenceRequired, String evidenceDescription,
                     String noEvidenceReason, String contextSummary) {}
}
