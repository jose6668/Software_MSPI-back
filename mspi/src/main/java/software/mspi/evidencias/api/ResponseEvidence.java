package software.mspi.evidencias.api;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ResponseEvidence {
    Metadata upload(Target target, long revision, String name, String description, InputStream input);
    Page list(Target target, int page, int size);
    Download download(Target target, UUID evidence);

    record Target(UUID actor, UUID company, UUID evaluation, UUID response, UUID correlation) {}

    record Metadata(UUID evidenciaId, String nombreOriginal, String tipoDetectado, int tamanoBytes,
                    String descripcion, UUID creadaPor, Instant creadaEn) {}

    record Page(List<Metadata> elementos, int pagina, int tamano, boolean hayMas) {}
    record Download(Metadata metadata, byte[] content) {}
}
