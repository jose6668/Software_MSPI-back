package software.mspi.evidencias.api;

import java.io.InputStream;
import java.util.UUID;
import software.mspi.acciones.api.ImprovementActions.Scope;

public interface ActionEvidence {
    ResponseEvidence.Metadata upload(Scope scope, UUID action, long revision, String name, String description, InputStream input);
    ResponseEvidence.Page list(Scope scope, UUID action, int page, int size);
    ResponseEvidence.Download download(Scope scope, UUID action, UUID evidence);

    /** Called within the action transaction after checking assignment or independent review. */
    boolean verify(Scope scope, UUID action, UUID evidence);
}
