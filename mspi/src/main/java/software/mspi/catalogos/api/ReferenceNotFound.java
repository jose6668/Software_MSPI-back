package software.mspi.catalogos.api;

public final class ReferenceNotFound extends RuntimeException {

    public ReferenceNotFound() {
        super("La referencia publicada no está disponible.");
    }
}
