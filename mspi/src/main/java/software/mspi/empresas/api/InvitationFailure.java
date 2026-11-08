package software.mspi.empresas.api;

public final class InvitationFailure extends RuntimeException {

    public enum Reason { UNAVAILABLE, DELIVERY, PASSWORD_POLICY }

    private final Reason reason;

    public InvitationFailure(Reason reason) {
        super("No se pudo completar la operación de invitación.");
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
