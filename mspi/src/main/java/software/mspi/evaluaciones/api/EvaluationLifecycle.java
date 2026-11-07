package software.mspi.evaluaciones.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

import software.mspi.evaluaciones.api.EvaluationDrafts.Evaluation;
import software.mspi.evaluaciones.api.EvaluationDrafts.Response;
import software.mspi.evaluaciones.api.EvaluationDrafts.Results;
import software.mspi.evaluaciones.api.EvaluationDrafts.Scope;

public interface EvaluationLifecycle {
    Evaluation submit(Scope scope, UUID evaluation, long revision, Map<UUID, UUID> selected, EvidenceVerifier verifier);
    Evaluation returnToDraft(Scope scope, UUID evaluation, long revision, String reason);
    Evaluation close(Scope scope, UUID evaluation, long revision, Map<UUID, UUID> reviewed, EvidenceVerifier verifier);
    Closure closure(Scope scope, UUID evaluation);
    Evaluation correct(Scope scope, UUID evaluation, long revision, UUID responsible, String reason);
    Response confirmInherited(Scope scope, UUID evaluation, UUID response, long revision);

    @FunctionalInterface
    interface EvidenceVerifier {
        Optional<String> verifiedHash(Scope scope, UUID evaluation, UUID response, UUID evidence);
    }

    record EvidenceRef(UUID evidenciaId, UUID evaluacionOrigenId, UUID respuestaOrigenId, String sha256) {}
    record Snapshot(String politica, Results resultados, Map<UUID, EvidenceRef> evidencias) {}
    record Closure(UUID evaluacionId, UUID versionCatalogoId, UUID versionMetodologiaId, UUID cerradaPor,
                   Instant cerradaEn, Snapshot instantanea) {}
}
