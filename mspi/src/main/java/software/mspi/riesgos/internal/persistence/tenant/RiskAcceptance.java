package software.mspi.riesgos.internal.persistence.tenant;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import software.mspi.riesgos.api.InherentRisks.Acceptance;

@Entity
@Table(name = "aceptaciones_riesgo")
class RiskAcceptance {
    @Id UUID id;
    @Column(name = "empresa_id") UUID company;
    @Column(name = "riesgo_id") UUID risk;
    @Column(name = "residual_id") UUID residual;
    @Column(name = "inherente_id") UUID inherent;
    @Column(name = "justificacion") String reason;
    @Column(name = "fecha_proxima_revision") LocalDate reviewDate;
    @Column(name = "aprobada_por") UUID author;
    @Column(name = "aprobada_en") Instant created;
    @Column(name = "revision_riesgo") long revision;

    protected RiskAcceptance() {}

    RiskAcceptance(RiskRecord root, UUID actor, String reason, LocalDate reviewDate, Instant now) {
        id = UUID.randomUUID();
        company = root.company;
        risk = root.id;
        residual = root.residual;
        inherent = root.current;
        this.reason = reason;
        this.reviewDate = reviewDate;
        author = actor;
        created = now;
        revision = root.revision;
    }

    Acceptance view(String state) {
        return new Acceptance(id, risk, inherent, residual, reason, reviewDate, author, created, state, revision);
    }
}
