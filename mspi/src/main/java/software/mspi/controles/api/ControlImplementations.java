package software.mspi.controles.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface ControlImplementations {
    Page<Implementation> list(Scope scope, int page, int size);
    Implementation get(Scope scope, UUID id);
    Page<Version> versions(Scope scope, UUID id, int page, int size);
    Page<Verification> verifications(Scope scope, UUID id, UUID version, int page, int size);
    Implementation create(Scope scope, UUID catalog, UUID element, Details details);
    Implementation start(Scope scope, UUID id, long revision);
    Implementation apply(Scope scope, UUID id, long revision, LocalDate date, UUID evidence, EvidenceVerifier verifier);
    Implementation change(Scope scope, UUID id, long revision, Details details, String reason);
    Verification verify(Scope scope, UUID id, long revision, Test input, EvidenceVerifier verifier);

    void requireVersion(Scope scope, UUID id, UUID version);
    <T> Optional<T> attachEvidence(Scope scope, UUID id, UUID version, long revision, Supplier<Optional<T>> publish);

    @FunctionalInterface
    interface EvidenceVerifier {
        Optional<String> verifiedHash(Scope scope, UUID implementation, UUID version, UUID evidence);
    }

    enum State { PROPUESTO, EN_IMPLEMENTACION, IMPLEMENTADO }
    enum Result { SIN_VERIFICAR, EFECTIVO, PARCIALMENTE_EFECTIVO, NO_EFECTIVO }
    enum Type { PREVENTIVO, DETECTIVO, CORRECTIVO }
    enum Mode { MANUAL, AUTOMATICO }

    record Scope(UUID actor, UUID company, UUID correlation) {}
    record Page<T>(List<T> elementos, int pagina, int tamano, boolean hayMas) {}
    record Details(String nombre, String descripcion, String alcance, String trabajo, UUID responsableId,
                   Type tipo, Mode modo) {}
    record Test(String prueba, String fundamento, int criteriosEvaluados, int criteriosCumplidos,
                LocalDate proximaRevision, UUID evidenciaId) {}
    record Implementation(UUID implementacionId, UUID versionCatalogoId, UUID elementoId, State estado,
                          long revision, Version versionActual, Result resultadoVigente, UUID ultimaVerificacionId,
                          String causaSinVigencia) {}
    record Version(UUID versionId, int numero, Details detalle, String motivoCambio, UUID creadaPor,
                   Instant creadaEn, Application aplicacion) {}
    record Application(LocalDate fechaAplicacion, UUID evidenciaId, UUID registradaPor, Instant registradaEn) {}
    record Verification(UUID verificacionId, UUID versionId, long revisionImplementacion, Result resultado,
                        String prueba, String fundamento, int criteriosEvaluados, int criteriosCumplidos,
                        LocalDate proximaRevision, UUID evidenciaId, UUID verificadaPor, Instant verificadaEn) {}
}
