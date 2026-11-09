package software.mspi.controles.internal.persistence.tenant;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import software.mspi.controles.api.ControlImplementations.Result;
import software.mspi.controles.api.ControlImplementations.Test;
import software.mspi.controles.api.ControlImplementations.Verification;

@Entity
@Immutable
@Table(name = "verificaciones_control")
class ControlVerification {
    @Id UUID id;
    @Column(name = "empresa_id") UUID company;
    @Column(name = "implementacion_id") UUID implementation;
    @Column(name = "version_id") UUID version;
    @Column(name = "revision") long revision;
    @Column(name = "prueba") String test;
    @Column(name = "fundamento") String rationale;
    @Column(name = "criterios_evaluados") int total;
    @Column(name = "criterios_cumplidos") int met;
    @Column(name = "fecha_verificacion") LocalDate date;
    @Column(name = "proxima_revision") LocalDate nextReview;
    @Column(name = "evidencia_id") UUID evidence;
    @Column(name = "huella") String hash;
    @Column(name = "verificada_por") UUID author;
    @Column(name = "verificada_en") Instant verified;

    protected ControlVerification() {}

    ControlVerification(ControlVersion version, long revision, Test test, LocalDate today, String hash, UUID actor) {
        id = UUID.randomUUID();
        company = version.company;
        implementation = version.implementation;
        this.version = version.id;
        this.revision = revision;
        this.test = test.prueba();
        rationale = test.fundamento();
        total = test.criteriosEvaluados();
        met = test.criteriosCumplidos();
        date = today;
        nextReview = test.proximaRevision();
        evidence = test.evidenciaId();
        this.hash = hash;
        author = actor;
        verified = Instant.now();
    }

    Result result() {
        if (met == 0) return Result.NO_EFECTIVO;
        return met == total ? Result.EFECTIVO : Result.PARCIALMENTE_EFECTIVO;
    }

    Verification view() {
        return new Verification(id, version, revision, result(), test, rationale, total, met, nextReview, evidence, author, verified);
    }
}
