package software.mspi.riesgos.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import software.mspi.riesgos.api.InherentRisks.Inputs;
import software.mspi.riesgos.api.InherentRisks.Level;
import software.mspi.riesgos.api.InherentRisks.Page;
import software.mspi.riesgos.api.InherentRisks.Scope;

public interface ResidualRisks {
    Page<Residual> residuals(Scope scope, UUID risk, int page, int size);
    Residual startResidual(Scope scope, UUID risk, long revision, Draft draft, String reason);
    Residual editResidual(Scope scope, UUID risk, UUID residual, long revision, Draft draft);
    Residual confirmResidual(Scope scope, UUID risk, UUID residual, long revision);

    record Draft(Inputs entradas, Boolean sinMitigacion, String justificacionSinMitigacion, List<UUID> verificaciones) {}
    record Proof(UUID implementacionId, UUID versionId, UUID verificacionId, UUID evidenciaAplicacionId,
                 UUID evidenciaVerificacionId, Instant venceEn) {}
    record Notice(String causa, UUID referenciaId, Instant detectadoEn, Instant resueltoEn) {}
    record Residual(UUID residualId, UUID riesgoId, UUID inherenteId, UUID versionMetodologiaId, UUID anteriorId,
                    String motivoReevaluacion, long revisionRiesgo, Draft datos, String estadoResultado, Level nivel,
                    BigDecimal probabilidadIndice, BigDecimal impactoIndice, boolean vigente, boolean requiereRevision,
                    List<Proof> pruebas, List<Notice> avisos, UUID creadaPor, Instant creadaEn,
                    UUID confirmadaPor, Instant confirmadaEn) {}
}
