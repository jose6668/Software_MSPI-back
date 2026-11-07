package software.mspi.empresas.api;

public final class PlatformFailure extends RuntimeException {

    public enum Reason { CONFLICT, MISSING, INCOMPATIBLE, MAIL_DISABLED, INVALID_ZONE }

    private final Reason reason;

    public PlatformFailure(Reason reason) {
        super("No se pudo completar la operación de plataforma.");
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
