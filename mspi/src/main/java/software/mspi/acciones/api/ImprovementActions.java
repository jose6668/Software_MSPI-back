package software.mspi.acciones.api;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface ImprovementActions {
    Page actions(Scope scope, int page, int size);
    Action get(Scope scope, UUID action);
    Action create(Scope scope, Draft draft);
    Action edit(Scope scope, UUID action, long revision, Draft draft, String reason);
    Action cancel(Scope scope, UUID action, long revision, String reason);
    void requireContextEvidence(Scope scope, UUID action, UUID evidence);
    <T> T readEvidence(Scope scope, UUID action, Supplier<T> operation);
    <T> Optional<T> attachEvidence(Scope scope, UUID action, long revision, Supplier<Optional<T>> publish);
    Action advance(Scope scope, UUID action, long revision, String comment);
    Action submit(Scope scope, UUID action, long revision, String comment, UUID evidence, Supplier<Boolean> verified);
    Action decide(Scope scope, UUID action, UUID delivery, long revision, boolean approve, String reason,
                  java.util.function.Predicate<UUID> verified);
    List<Event> history(Scope scope, UUID action, int page, int size);

    record Event(UUID eventoId, String tipo, String comentario, UUID autorId, UUID responsableMembresiaId,
                 UUID entregaId, UUID evidenciaId, Instant creadaEn) {}

    record Scope(UUID actor, UUID company, UUID correlation) {}
    record Draft(UUID risk, UUID finding, String description, String expectedResult, UUID assigneeMembership,
                 LocalDate dueDate, boolean evidenceRequired, String evidenceDescription,
                 String noEvidenceReason, String contextSummary, List<ContextEvidence> contextEvidence) {}
    record ContextEvidence(UUID evidenceId, String reason) {}
    record Action(UUID accionId, String codigo, UUID riesgoId, UUID hallazgoId, String nombreActivo, String descripcion,
                  String resultadoEsperado, UUID responsableMembresiaId, LocalDate fechaLimite, String estado,
                  boolean vencida, boolean evidenciaRequerida, String descripcionEvidencia, String justificacionSinEvidencia,
                  String resumenContextoRa, List<ContextEvidenceView> evidenciasContexto, long revision,
                  String motivoCambio, UUID creadaPor, Instant creadaEn, UUID entregaVigenteId, Instant finalizadaEn) {}
    record ContextEvidenceView(UUID evidenciaId, String motivoContexto) {}
    record Page(List<Action> elementos, int pagina, int tamano, boolean hayMas) {}
}
