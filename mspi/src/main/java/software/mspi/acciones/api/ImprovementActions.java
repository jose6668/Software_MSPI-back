package software.mspi.acciones.api;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ImprovementActions {
    Page actions(Scope scope, int page, int size);
    Action get(Scope scope, UUID action);
    Action create(Scope scope, Draft draft);
    Action edit(Scope scope, UUID action, long revision, Draft draft, String reason);
    Action cancel(Scope scope, UUID action, long revision, String reason);
    void requireContextEvidence(Scope scope, UUID action, UUID evidence);

    record Scope(UUID actor, UUID company, UUID correlation) {}
    record Draft(UUID risk, UUID finding, String description, String expectedResult, UUID assigneeMembership,
                 LocalDate dueDate, boolean evidenceRequired, String evidenceDescription,
                 String noEvidenceReason, String contextSummary, List<ContextEvidence> contextEvidence) {}
    record ContextEvidence(UUID evidenceId, String reason) {}
    record Action(UUID accionId, String codigo, UUID riesgoId, UUID hallazgoId, String nombreActivo, String descripcion,
                  String resultadoEsperado, UUID responsableMembresiaId, LocalDate fechaLimite, String estado,
                  boolean vencida, boolean evidenciaRequerida, String descripcionEvidencia, String justificacionSinEvidencia,
                  String resumenContextoRa, List<ContextEvidenceView> evidenciasContexto, long revision,
                  String motivoCambio, UUID creadaPor, Instant creadaEn) {}
    record ContextEvidenceView(UUID evidenciaId, String motivoContexto) {}
    record Page(List<Action> elementos, int pagina, int tamano, boolean hayMas) {}
}
