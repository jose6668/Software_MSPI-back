package software.mspi.evaluaciones.api;

import java.util.List;
import java.util.UUID;

public final class EvaluationBlocked extends RuntimeException {
    private final List<Issue> issues;

    public EvaluationBlocked(List<Issue> issues) {
        super("Hay respuestas pendientes de revisión o cierre.");
        this.issues = List.copyOf(issues);
    }

    public List<Issue> issues() {
        return issues;
    }

    public record Issue(UUID respuestaId, String campo, String codigo, String detalle) {}
}
