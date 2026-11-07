package software.mspi.auditoria.internal;

import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.mspi.auditoria.api.ActivityLog;

@Component
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
final class JpaActivityLog implements ActivityLog {

    @PersistenceContext(unitName = "enterprise")
    private EntityManager entities;

    @Override
    public void append(Event event) {
        entities.createNativeQuery("""
                INSERT INTO eventos_actividad(id, empresa_id, actor_id, accion, tipo_objeto, objeto_id,
                    estado_anterior, estado_nuevo, motivo, correlacion_id)
                VALUES (:id, :company, :actor, :action, :type, :object, :previous, :state, :reason, :correlation)
                """)
                .setParameter("id", UUID.randomUUID()).setParameter("company", event.company())
                .setParameter("actor", event.actor()).setParameter("action", event.action())
                .setParameter("type", event.objectType()).setParameter("object", event.objectId())
                .setParameter("previous", event.previousState()).setParameter("state", event.state())
                .setParameter("reason", event.reason()).setParameter("correlation", event.correlation())
                .executeUpdate();
    }
}
