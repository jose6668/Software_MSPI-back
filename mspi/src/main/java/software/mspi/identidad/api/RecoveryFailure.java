package software.mspi.identidad.api;

public final class RecoveryFailure extends RuntimeException {

    public enum Reason { UNAVAILABLE, PASSWORD_LENGTH }

    private final Reason reason;

    public RecoveryFailure(Reason reason) {
        super("No se pudo completar la recuperación de acceso.");
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
