package software.mspi.riesgos.internal.persistence.tenant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import software.mspi.riesgos.api.InherentRisks.*;

@Entity
@Table(name = "valoraciones_inherentes")
class InherentAssessment {
    @Id UUID id;
    @Column(name = "empresa_id", updatable = false) UUID company;
    @Column(name = "riesgo_id", updatable = false) UUID risk;
    @Column(name = "version_metodologia_id", updatable = false) UUID method;
    @Column(name = "anterior_id", updatable = false) UUID previous;
    @Column(name = "motivo_reevaluacion", updatable = false) String reason;
    @Column(name = "contexto") long context;
    @Column(name = "probabilidad") @Enumerated(EnumType.STRING) Probability probability;
    @Column(name = "impacto") @Enumerated(EnumType.STRING) Impact impact;
    @Column(name = "periodo") String period;
    @Column(name = "fundamento") String rationale;
    @Column(name = "supuestos") String assumptions;
    @Column(name = "fuente") String source;
    @Column(name = "probabilidad_indice") BigDecimal probabilityIndex;
    @Column(name = "impacto_indice") BigDecimal impactIndex;
    @Column(name = "nivel") @Enumerated(EnumType.STRING) Level level;
    @Column(name = "activo_base_id") UUID assetBase;
    @Column(name = "amenaza_base") String threatBase;
    @Column(name = "vulnerabilidad_base") String vulnerabilityBase;
    @Column(name = "descripcion_base") String descriptionBase;
    @Column(name = "creada_por", updatable = false) UUID author;
    @Column(name = "creada_en", updatable = false) Instant created;
    @Column(name = "confirmada_por") UUID confirmedBy;
    @Column(name = "confirmada_en") Instant confirmedAt;

    protected InherentAssessment() {}

    InherentAssessment(RiskRecord root, UUID method, UUID author, Inputs inputs, String reason) {
        id = UUID.randomUUID();
        company = root.company;
        risk = root.id;
        this.method = method;
        previous = root.current;
        this.reason = reason;
        this.author = author;
        created = Instant.now();
        update(inputs, root.context);
    }

    void update(Inputs inputs, long context) {
        probability = inputs.probabilidad();
        impact = inputs.impacto();
        period = inputs.periodoReferencia();
        rationale = inputs.fundamento();
        assumptions = inputs.supuestos();
        source = inputs.fuente();
        this.context = context;
    }

    List<String> missing() {
        var fields = new ArrayList<String>();
        if (probability == null) fields.add("probabilidad");
        if (impact == null) fields.add("impacto");
        if (period == null) fields.add("periodoReferencia");
        if (rationale == null) fields.add("fundamento");
        if (assumptions == null) fields.add("supuestos");
        if (source == null) fields.add("fuente");
        return fields;
    }

    void confirm(RiskRecord root, RiskMatrix.Calculated result, UUID actor) {
        level = result.level();
        probabilityIndex = result.probabilityIndex();
        impactIndex = result.impactIndex();
        assetBase = root.asset;
        threatBase = root.threat;
        vulnerabilityBase = root.vulnerability;
        descriptionBase = root.description;
        confirmedBy = actor;
        confirmedAt = Instant.now();
    }

    Assessment view(RiskRecord root) {
        var inputs = new Inputs(probability, impact, period, rationale, assumptions, source);
        var base = confirmedAt == null ? null : new Base(assetBase, threatBase, vulnerabilityBase, descriptionBase);
        return new Assessment(id, risk, method, previous, reason, root.revision, inputs,
                confirmedAt == null ? "BORRADOR" : "CONFIRMADA", level, probabilityIndex, impactIndex,
                context != root.context, base, author, created, confirmedBy, confirmedAt);
    }
}
