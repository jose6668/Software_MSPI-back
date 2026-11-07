package software.mspi.auditoria.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Appends activity in the caller's existing enterprise transaction. */
public interface ActivityLog {

    void append(Event event);

    List<Entry> assetHistory(UUID company, UUID asset, int limit, long offset);

    record Event(UUID company, UUID actor, String action, String objectType, UUID objectId,
                 String previousState, String state, String reason, UUID correlation, List<String> changedFields,
                 ReferenceChange referenceChange) {
        public Event(UUID company, UUID actor, String action, String objectType, UUID objectId,
                     String previousState, String state, String reason, UUID correlation) {
            this(company, actor, action, objectType, objectId, previousState, state, reason, correlation, List.of(), null);
        }
    }

    record Entry(UUID actorId, String accion, String estadoAnterior, String estadoNuevo, String motivo,
                 UUID correlationId, Instant registradoEn, List<String> camposModificados, ReferenceChange referencias) {}

    record AssetReferences(UUID procesoId, UUID propietarioId, UUID custodioId) {}

    record ReferenceChange(AssetReferences anteriores, AssetReferences nuevas) {}
}
