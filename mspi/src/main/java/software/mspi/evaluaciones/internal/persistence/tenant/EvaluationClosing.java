package software.mspi.evaluaciones.internal.persistence.tenant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import software.mspi.evaluaciones.api.EvaluationBlocked;
import software.mspi.evaluaciones.api.EvaluationBlocked.Issue;
import software.mspi.evaluaciones.api.EvaluationDrafts.Applicability;
import software.mspi.evaluaciones.api.EvaluationDrafts.Scope;
import software.mspi.evaluaciones.api.EvaluationLifecycle.EvidenceRef;
import software.mspi.evaluaciones.api.EvaluationLifecycle.EvidenceVerifier;

final class EvaluationClosing {
    static final String POLICY = "F1-1_EVIDENCIA_V1";

    private EvaluationClosing() {}

    static Map<UUID, EvidenceRef> validate(Scope scope, EvaluationRecord evaluation, List<EvaluationResponse> responses,
            Map<UUID, UUID> selected, Map<UUID, EvidenceRef> previous, EvidenceVerifier verifier) {
        var issues = new ArrayList<Issue>();
        var proofs = new LinkedHashMap<UUID, EvidenceRef>();
        var unexpected = new java.util.HashSet<>(selected.keySet());
        for (var response : responses) {
            unexpected.remove(response.id);
            if (!response.inheritedConfirmed) {
                issues.add(issue(response, "confirmacionHeredada", "RESPUESTA_HEREDADA_PENDIENTE",
                        "Confirme o actualice la respuesta heredada."));
            }
            switch (response.applicability) {
                case SIN_DECIDIR -> issues.add(issue(response, "aplicabilidad", "APLICABILIDAD_PENDIENTE",
                        "Resuelva la aplicabilidad."));
                case APLICA -> {
                    if (response.score == null) {
                        issues.add(issue(response, "calificacion", "CALIFICACION_PENDIENTE", "Indique una calificación."));
                    }
                    if (response.rationale == null || response.rationale.isBlank()) {
                        issues.add(issue(response, "fundamento", "FUNDAMENTO_REQUERIDO", "Fundamente la calificación."));
                    }
                }
                case NO_APLICA -> {
                    if (response.justification == null || response.justification.isBlank()) {
                        issues.add(issue(response, "justificacionAplicabilidad", "JUSTIFICACION_REQUERIDA",
                                "Justifique por qué el control no aplica."));
                    }
                }
            }
            UUID chosen = selected.get(response.id);
            if (chosen != null) {
                EvidenceRef inherited = response.previousResponse == null ? null : previous.get(response.previousResponse);
                var proof = response.applicability == Applicability.APLICA
                        ? verify(scope, evaluation.id, response.id, chosen, inherited, verifier) : null;
                if (proof == null) {
                    issues.add(issue(response, "evidenciaId", "EVIDENCIA_NO_VALIDA",
                            "Seleccione un adjunto disponible e íntegro asociado a la respuesta."));
                } else {
                    proofs.put(response.id, proof);
                }
            } else if (response.score != null && response.score > 0) {
                issues.add(issue(response, "evidenciaId", "EVIDENCIA_REQUERIDA", "Seleccione un adjunto para la calificación."));
            }
        }
        if (!unexpected.isEmpty()) {
            issues.add(new Issue(null, "evidencias", "RESPUESTA_NO_DISPONIBLE",
                    "Hay selecciones que no pertenecen a la evaluación."));
        }
        if (!issues.isEmpty()) throw new EvaluationBlocked(issues);
        return proofs;
    }

    private static EvidenceRef verify(Scope scope, UUID evaluation, UUID response, UUID chosen,
            EvidenceRef inherited, EvidenceVerifier verifier) {
        if (inherited != null && chosen.equals(inherited.evidenciaId())) {
            return verifier.verifiedHash(scope, inherited.evaluacionOrigenId(), inherited.respuestaOrigenId(), chosen)
                    .filter(inherited.sha256()::equals).map(hash -> inherited).orElse(null);
        }
        return verifier.verifiedHash(scope, evaluation, response, chosen)
                .map(hash -> new EvidenceRef(chosen, evaluation, response, hash)).orElse(null);
    }

    private static Issue issue(EvaluationResponse response, String field, String code, String detail) {
        return new Issue(response.id, field, code, detail);
    }
}
