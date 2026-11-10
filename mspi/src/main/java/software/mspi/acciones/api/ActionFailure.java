package software.mspi.acciones.api;

public final class ActionFailure extends RuntimeException {
    private final Reason reason;

    public ActionFailure(Reason reason) { this.reason = reason; }
    public Reason reason() { return reason; }

    public enum Reason { MISSING, INVALID, STATE, EVIDENCE }
}
