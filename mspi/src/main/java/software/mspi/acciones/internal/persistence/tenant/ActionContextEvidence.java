package software.mspi.acciones.internal.persistence.tenant;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "accion_evidencias_contexto")
class ActionContextEvidence {
    @Id UUID id;
    @Column(name = "empresa_id", updatable = false) UUID company;
    @Column(name = "accion_id", updatable = false) UUID action;
    @Column(name = "evidencia_id", updatable = false) UUID evidence;
    @Column(name = "motivo_contexto", updatable = false) String reason;
    @Column(name = "creada_por", updatable = false) UUID author;
    @Column(name = "creada_en", updatable = false) Instant created;

    protected ActionContextEvidence() {}

    ActionContextEvidence(UUID company, UUID action, UUID evidence, String reason, UUID actor, Instant now) {
        id = UUID.randomUUID();
        this.company = company;
        this.action = action;
        this.evidence = evidence;
        this.reason = reason;
        author = actor;
        created = now;
    }
}
