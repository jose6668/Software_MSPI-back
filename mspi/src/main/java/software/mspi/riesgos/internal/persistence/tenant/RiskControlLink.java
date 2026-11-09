package software.mspi.riesgos.internal.persistence.tenant;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import software.mspi.riesgos.api.InherentRisks.Effect;
import software.mspi.riesgos.api.InherentRisks.Link;

@Entity
@Immutable
@Table(name = "riesgos_implementaciones")
class RiskControlLink {
    @Id UUID id;
    @Column(name = "empresa_id") UUID company;
    @Column(name = "riesgo_id") UUID risk;
    @Column(name = "implementacion_id") UUID implementation;
    @Column(name = "efecto_esperado") @Enumerated(EnumType.STRING) Effect effect;
    @Column(name = "justificacion") String rationale;
    @Column(name = "creada_por") UUID author;
    @Column(name = "creada_en") Instant created;

    protected RiskControlLink() {}

    RiskControlLink(RiskRecord root, UUID implementation, Effect effect, String rationale, UUID author) {
        id = UUID.randomUUID();
        company = root.company;
        risk = root.id;
        this.implementation = implementation;
        this.effect = effect;
        this.rationale = rationale;
        this.author = author;
        created = Instant.now();
    }

    Link view(long revision) {
        return new Link(id, implementation, effect, rationale, revision, author, created);
    }
}
