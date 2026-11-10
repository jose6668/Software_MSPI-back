package software.mspi.riesgos.api;

import java.util.List;

public final class RiskFailure extends RuntimeException {
    public enum Reason { MISSING, STATE, CODE, ASSET, RESPONSIBLE, IMPLEMENTATION, DUPLICATE_LINK, DRAFT_EXISTS,
        INCOMPLETE, STALE, REASON, METHODOLOGY, BASE_INHERENTE, RESIDUAL_PROOF, RESIDUAL_MITIGATION, RESIDUAL_DRAFT_EXISTS,
        RESIDUAL_INCOMPLETE, LIFECYCLE_ASSESSMENT, ACCEPTANCE_REQUIRED, ACCEPTANCE_DATE, PLAN_REQUIRED, OPEN_ACTIONS,
        CANCELLATION_EXPLANATION }

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
