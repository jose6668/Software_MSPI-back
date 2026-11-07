package software.mspi.activos.api;

public final class CatalogFailure extends RuntimeException {

    public enum Reason { MISSING, REFERENCE, ARCHIVED }

    private final Reason reason;

    public CatalogFailure(Reason reason) {
        super("La operación sobre el catálogo empresarial no está disponible.");
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
