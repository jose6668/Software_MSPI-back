package software.mspi.auditoria.internal;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.mspi.auditoria.api.ActivityLog;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
final class JpaActivityLog implements ActivityLog {

    private final JsonMapper mapper;

    @PersistenceContext(unitName = "enterprise")
    private EntityManager entities;

    JpaActivityLog(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void append(Event event) {
        entities.createNativeQuery("""
                INSERT INTO eventos_actividad(id, empresa_id, actor_id, accion, tipo_objeto, objeto_id,
                    estado_anterior, estado_nuevo, motivo, correlacion_id, campos_modificados, cambios_referencias)
                VALUES (:id, :company, :actor, :action, :type, :object, :previous, :state, :reason, :correlation,
                    string_to_array(:fields, ','), cast(:references as jsonb))
                """)
                .setParameter("id", UUID.randomUUID()).setParameter("company", event.company())
                .setParameter("actor", event.actor()).setParameter("action", event.action())
                .setParameter("type", event.objectType()).setParameter("object", event.objectId())
                .setParameter("previous", event.previousState()).setParameter("state", event.state())
                .setParameter("reason", event.reason()).setParameter("correlation", event.correlation())
                .setParameter("fields", String.join(",", event.changedFields()))
                .setParameter("references", event.referenceChange() == null ? null : mapper.writeValueAsString(event.referenceChange()))
                .executeUpdate();
    }

    @Override
    public List<Entry> assetHistory(UUID company, UUID asset, int limit, long offset) {
        return entities.unwrap(Session.class).doReturningWork(connection -> {
            try (var statement = connection.prepareStatement("SELECT * FROM historial_activo(?, ?, ?, ?)")) {
                statement.setObject(1, company);
                statement.setObject(2, asset);
                statement.setInt(3, limit);
                statement.setLong(4, offset);
                try (var rows = statement.executeQuery()) {
                    var entries = new ArrayList<Entry>();
                    while (rows.next()) {
                        var fields = rows.getArray("campos_modificados");
                        try {
                            String references = rows.getString("cambios_referencias");
                            entries.add(new Entry(rows.getObject("actor_id", UUID.class), rows.getString("accion"),
                                    rows.getString("estado_anterior"), rows.getString("estado_nuevo"),
                                    rows.getString("motivo"), rows.getObject("correlacion_id", UUID.class),
                                    rows.getObject("creado_en", OffsetDateTime.class).toInstant(),
                                    List.of((String[]) fields.getArray()),
                                    references == null ? null : mapper.readValue(references, ReferenceChange.class)));
                        } finally {
                            fields.free();
                        }
                    }
                    return entries;
                }
            }
        });
    }
}
