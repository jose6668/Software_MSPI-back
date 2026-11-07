package software.mspi.identidad.api;

import java.util.UUID;

/** Resolves the current server identity and checks that its account remains enabled. */
public interface ActiveSession {
    UUID requireActiveUser();
}
