package software.mspi.activos.api;

import java.util.List;

public final class ClassificationFailure extends RuntimeException {

    public enum Reason { INVALID, INCOMPLETE, DRAFT_EXISTS, CONFIRMED }

    private final Reason reason;
    private final List<String> fields;

    public ClassificationFailure(Reason reason, List<String> fields) {
        super("No se pudo aplicar la clasificación solicitada.");
        this.reason = reason;
        this.fields = List.copyOf(fields);
    }

    public Reason reason() { return reason; }

    public List<String> fields() { return fields; }
}
