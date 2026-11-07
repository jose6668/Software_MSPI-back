package software.mspi.auditoria.api;

import java.util.UUID;

/** Appends activity in the caller's existing enterprise transaction. */
public interface ActivityLog {

    void append(Event event);

    record Event(UUID company, UUID actor, String action, String objectType, UUID objectId,
                 String previousState, String state, String reason, UUID correlation) {}
}
