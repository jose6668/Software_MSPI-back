package software.mspi.evaluaciones.api;

import java.util.List;

public final class EvaluationFailure extends RuntimeException {

    public enum Reason {
        MISSING, NOT_EDITABLE, RESPONSIBLE, VERSION, INVALID_ANSWER, STATE, POLICY, NOT_INHERITED, CORRECTION_EXISTS
    }

    private final Reason reason;
    private final List<String> fields;

    public EvaluationFailure(Reason reason) {
        this(reason, List.of());
    }

    public EvaluationFailure(Reason reason, List<String> fields) {
        super("La operación de evaluación no se pudo completar.");
        this.reason = reason;
        this.fields = List.copyOf(fields);
    }

    public Reason reason() { return reason; }
    public List<String> fields() { return fields; }
}
