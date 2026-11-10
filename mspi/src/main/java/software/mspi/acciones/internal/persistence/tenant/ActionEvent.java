package software.mspi.acciones.internal.persistence.tenant;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "eventos_accion")
class ActionEvent {
    @Id UUID id;
    @Column(name = "empresa_id", updatable = false) UUID company;
    @Column(name = "accion_id", updatable = false) UUID action;
    @Column(name = "tipo_evento", updatable = false) String type;
    @Column(name = "comentario", updatable = false) String comment;
    @Column(name = "autor_id", updatable = false) UUID author;
    @Column(name = "responsable_membresia_id", updatable = false) UUID assignee;
    @Column(name = "creada_en", updatable = false) Instant created;
    @Column(name = "entrega_id", updatable = false) UUID delivery;
    @Column(name = "evidencia_id", updatable = false) UUID evidence;

    protected ActionEvent() {}

    software.mspi.acciones.api.ImprovementActions.Event view() {
        return new software.mspi.acciones.api.ImprovementActions.Event(id, type, comment, author, assignee, delivery, evidence, created);
    }

    ActionEvent(ImprovementActionRecord action, String type, String comment, UUID actor, Instant now) {
        id = UUID.randomUUID();
        company = action.company;
        this.action = action.id;
        this.type = type;
        this.comment = comment;
        author = actor;
        assignee = action.assignee;
        created = now;
    }
}
