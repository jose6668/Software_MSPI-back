package software.mspi.riesgos.internal.persistence.tenant;

import java.math.BigDecimal;
import java.util.UUID;

import software.mspi.riesgos.api.InherentRisks.Impact;
import software.mspi.riesgos.api.InherentRisks.Level;
import software.mspi.riesgos.api.InherentRisks.Probability;
import software.mspi.riesgos.api.RiskFailure;
import tools.jackson.databind.JsonNode;

final class RiskMatrix {
    static final UUID V1 = UUID.fromString("4cbd3e6a-0382-4a97-b090-d68fe1105320");

    private RiskMatrix() {}

    static Calculated calculate(JsonNode rules, Probability probability, Impact impact) {
        var p = rules.path("probabilidad").path(probability.name()).path("indice");
        var i = rules.path("impacto").path(impact.name()).path("indice");
        var level = rules.path("matriz").path(probability.name()).path(impact.name());
        if (!p.isNumber() || !i.isNumber() || !level.isString()) {
            throw new RiskFailure(RiskFailure.Reason.METHODOLOGY);
        }
        try {
            return new Calculated(Level.valueOf(level.asString()), p.decimalValue(), i.decimalValue());
        } catch (IllegalArgumentException invalid) {
            throw new RiskFailure(RiskFailure.Reason.METHODOLOGY);
        }
    }

    record Calculated(Level level, BigDecimal probabilityIndex, BigDecimal impactIndex) {}
}
