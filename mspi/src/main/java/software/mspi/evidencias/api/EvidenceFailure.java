package software.mspi.evidencias.api;

public final class EvidenceFailure extends RuntimeException {
    public enum Reason { INVALID_FILE, TOO_LARGE, MISSING, QUOTA, QUOTA_NOT_CONFIGURED, STORAGE, MALWARE, INSPECTION_UNAVAILABLE }

    private final Reason reason;

    public EvidenceFailure(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
