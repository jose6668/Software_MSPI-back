package software.mspi.riesgos.internal.persistence.tenant;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import software.mspi.riesgos.api.ResidualRisks.Proof;

@Entity
@Immutable
@Table(name = "residual_verificaciones")
class ResidualControl {
    @Id UUID id;
    @Column(name = "empresa_id") UUID company;
    @Column(name = "riesgo_id") UUID risk;
    @Column(name = "residual_id") UUID residual;
    @Column(name = "implementacion_id") UUID implementation;
    @Column(name = "version_id") UUID version;
    @Column(name = "verificacion_id") UUID verification;
    @Column(name = "evidencia_aplicacion_id") UUID applicationEvidence;
    @Column(name = "evidencia_verificacion_id") UUID verificationEvidence;
    @Column(name = "vence_en") Instant expires;

    protected ResidualControl() {}

    ResidualControl(ResidualAssessment assessment, Proof proof) {
        id = UUID.randomUUID();
        company = assessment.company;
        risk = assessment.risk;
        residual = assessment.id;
        implementation = proof.implementacionId();
        version = proof.versionId();
        verification = proof.verificacionId();
        applicationEvidence = proof.evidenciaAplicacionId();
        verificationEvidence = proof.evidenciaVerificacionId();
        expires = proof.venceEn();
    }

    Proof view() {
        return new Proof(implementation, version, verification, applicationEvidence, verificationEvidence, expires);
    }
}
