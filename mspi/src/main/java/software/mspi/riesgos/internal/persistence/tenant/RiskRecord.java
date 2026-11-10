package software.mspi.riesgos.internal.persistence.tenant;

import java.time.Instant;
import java.util.UUID;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import software.mspi.activos.api.BusinessCode;
import software.mspi.riesgos.api.InherentRisks.*;

@Entity
@Table(name = "riesgos")
class RiskRecord {
    @Id UUID id;
    @Column(name = "empresa_id", updatable = false) UUID company;
    @Column(name = "codigo", updatable = false) String code;
    @Column(name = "codigo_normalizado", updatable = false) String normalizedCode;
    @Column(name = "activo_principal_id") UUID asset;
    @Column(name = "amenaza") String threat;
    @Column(name = "vulnerabilidad") String vulnerability;
    @Column(name = "descripcion") String description;
    @Column(name = "responsable_id") UUID responsible;
    @Column(name = "tratamiento_propuesto") @Enumerated(EnumType.STRING) Treatment treatment;
    @Column(name = "estado") @Enumerated(EnumType.STRING) State state;
    @Column(name = "revision") long revision;
    @Column(name = "contexto") long context;
    @Column(name = "inherente_vigente_id") UUID current;
    @Column(name = "residual_vigente_id") UUID residual;
    @Column(name = "motivo_revision") String reviewReason;
    @Column(name = "creada_por", updatable = false) UUID author;
    @Column(name = "creada_en", updatable = false) Instant created;
    @Column(name = "riesgo_relacionado_id", updatable = false) UUID related;
    @Column(name = "justificacion_cierre") String closureReason;
    @Column(name = "cerrado_por") UUID closedBy;
    @Column(name = "cerrado_en") Instant closedAt;
    @Column(name = "aceptacion_cierre_id") UUID closureAcceptance;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cancelaciones_cierre") Map<String, String> closureCancellations;

    protected RiskRecord() {}

    RiskRecord(Scope scope, String code, Details details) {
        id = UUID.randomUUID();
        company = scope.company();
        this.code = code;
        normalizedCode = BusinessCode.normalize(code);
        state = State.IDENTIFICADO;
        author = scope.actor();
        created = Instant.now();
        update(details);
    }

    void update(Details details) {
        asset = details.activoPrincipalId();
        threat = details.amenaza();
        vulnerability = details.vulnerabilidad();
        description = details.descripcion();
        responsible = details.responsableId();
        treatment = details.tratamientoPropuesto();
    }

    boolean changesBasis(Details details) {
        return !asset.equals(details.activoPrincipalId()) || !threat.equals(details.amenaza())
                || !vulnerability.equals(details.vulnerabilidad()) || !description.equals(details.descripcion());
    }

    Risk view(Level level) {
        return new Risk(id, code, new Details(asset, threat, vulnerability, description, responsible, treatment),
                state, revision, current, level, reviewReason != null, reviewReason, author, created, related,
                closedAt == null ? null : new Closure(closureReason, closedBy, closedAt, current, residual,
                        closureAcceptance, Map.copyOf(closureCancellations)));
    }
}
