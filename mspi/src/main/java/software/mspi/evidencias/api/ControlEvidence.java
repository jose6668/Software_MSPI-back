package software.mspi.evidencias.api;

import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

import software.mspi.controles.api.ControlImplementations.Scope;
import software.mspi.evidencias.api.ResponseEvidence.Download;
import software.mspi.evidencias.api.ResponseEvidence.Metadata;
import software.mspi.evidencias.api.ResponseEvidence.Page;

public interface ControlEvidence {
    Metadata upload(Target target, long revision, String name, String description, InputStream input);
    Page list(Target target, int page, int size);
    Download download(Target target, UUID evidence);
    Optional<String> verifiedHash(Scope scope, UUID implementation, UUID version, UUID evidence);

    record Target(Scope scope, UUID implementation, UUID version) {}
}
