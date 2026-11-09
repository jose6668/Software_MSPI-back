package software.mspi.riesgos.internal.persistence.tenant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import software.mspi.riesgos.api.InherentRisks.*;
import software.mspi.riesgos.api.ResidualRisks.*;

@Entity
@Table(name = "valoraciones_residuales")
class ResidualAssessment {
    @Id UUID id;
    @Column(name = "empresa_id", updatable = false) UUID company;
    @Column(name = "riesgo_id", updatable = false) UUID risk;
    @Column(name = "inherente_id") UUID inherent;
    @Column(name = "version_metodologia_id") UUID method;
    @Column(name = "anterior_id", updatable = false) UUID previous;
    @Column(name = "motivo_reevaluacion", updatable = false) String reason;
    @Column(name = "probabilidad") @Enumerated(EnumType.STRING) Probability probability;
    @Column(name = "impacto") @Enumerated(EnumType.STRING) Impact impact;
    @Column(name = "periodo") String period;
    @Column(name = "fundamento") String rationale;
    @Column(name = "supuestos") String assumptions;
    @Column(name = "fuente") String source;
    @Column(name = "sin_mitigacion") Boolean withoutMitigation;
    @Column(name = "justificacion_sin_mitigacion") String absenceReason;
    @Column(name = "seleccion", columnDefinition = "uuid[]") UUID[] selected;
    @Column(name = "nivel") @Enumerated(EnumType.STRING) Level level;
    @Column(name = "probabilidad_indice") BigDecimal probabilityIndex;
    @Column(name = "impacto_indice") BigDecimal impactIndex;
    @Column(name = "creada_por", updatable = false) UUID author;
    @Column(name = "creada_en", updatable = false) Instant created;
    @Column(name = "confirmada_por") UUID confirmedBy;
    @Column(name = "confirmada_en") Instant confirmedAt;

    protected ResidualAssessment() {}

    ResidualAssessment(RiskRecord root, InherentAssessment base, UUID author, Draft draft, String reason) {
        id = UUID.randomUUID();
        company = root.company;
        risk = root.id;
        previous = root.residual;
        this.reason = reason;
        this.author = author;
        created = Instant.now();
        update(base, draft);
    }

    void update(InherentAssessment base, Draft draft) {
        inherent = base.id;
        method = base.method;
        var input = draft.entradas();
        probability = input.probabilidad();
        impact = input.impacto();
        period = input.periodoReferencia();
        rationale = input.fundamento();
        assumptions = input.supuestos();
        source = input.fuente();
        withoutMitigation = draft.sinMitigacion();
        absenceReason = draft.justificacionSinMitigacion();
        selected = draft.verificaciones().toArray(UUID[]::new);
    }

    Inputs inputs() {
        return new Inputs(probability, impact, period, rationale, assumptions, source);
    }

    Residual view(RiskRecord root, List<Proof> proofs, List<Notice> notices) {
        var draft = new Draft(inputs(), withoutMitigation, absenceReason, Arrays.asList(selected));
        boolean stale = notices.stream().anyMatch(notice -> notice.resueltoEn() == null)
                || (confirmedAt == null && (root.reviewReason != null || !inherent.equals(root.current)));
        return new Residual(id, risk, inherent, method, previous, reason, root.revision, draft,
                confirmedAt == null ? "BORRADOR" : "CONFIRMADA", level, probabilityIndex, impactIndex,
                id.equals(root.residual) && !stale, stale, proofs, notices, author, created, confirmedBy, confirmedAt);
    }
}
