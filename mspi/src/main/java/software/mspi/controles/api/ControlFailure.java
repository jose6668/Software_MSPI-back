package software.mspi.controles.api;

public final class ControlFailure extends RuntimeException {
    public enum Reason { MISSING, STATE, CATALOG, RESPONSIBLE, DATES, CRITERIA, EVIDENCE, NEW_EVIDENCE }

    private final Reason reason;

    public ControlFailure(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
