package software.mspi.riesgos.api;

import java.util.List;

public final class RiskFailure extends RuntimeException {
    public enum Reason { MISSING, STATE, CODE, ASSET, RESPONSIBLE, IMPLEMENTATION, DUPLICATE_LINK, DRAFT_EXISTS,
        INCOMPLETE, STALE, REASON, METHODOLOGY }

    private final Reason reason;
    private final List<String> fields;

    public RiskFailure(Reason reason) {
        this(reason, List.of());
    }

    public RiskFailure(Reason reason, List<String> fields) {
        super(reason.name());
        this.reason = reason;
        this.fields = List.copyOf(fields);
    }

    public Reason reason() { return reason; }
    public List<String> fields() { return fields; }
}
