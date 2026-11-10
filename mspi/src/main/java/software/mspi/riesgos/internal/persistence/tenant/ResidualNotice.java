package software.mspi.riesgos.internal.persistence.tenant;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "avisos_revision_residual")
class ResidualNotice {
    @Id UUID id;
    @Column(name = "empresa_id") UUID company;
    @Column(name = "residual_id") UUID residual;
    @Column(name = "causa") String reason;
    @Column(name = "referencia_id") UUID reference;
    @Column(name = "detectado_en") Instant detected;

    protected ResidualNotice() {}
}
