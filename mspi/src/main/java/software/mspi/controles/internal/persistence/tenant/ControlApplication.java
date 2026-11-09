package software.mspi.controles.internal.persistence.tenant;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import software.mspi.controles.api.ControlImplementations.Application;

@Entity
@Immutable
@Table(name = "aplicaciones_control")
class ControlApplication {
    @Id @Column(name = "version_id") UUID version;
    @Column(name = "empresa_id") UUID company;
    @Column(name = "implementacion_id") UUID implementation;
    @Column(name = "fecha_aplicacion") LocalDate date;
    @Column(name = "evidencia_id") UUID evidence;
    @Column(name = "huella") String hash;
    @Column(name = "registrada_por") UUID author;
    @Column(name = "registrada_en") Instant registered;

    protected ControlApplication() {}

    ControlApplication(ControlVersion version, LocalDate date, UUID evidence, String hash, UUID actor) {
        this.version = version.id;
        company = version.company;
        implementation = version.implementation;
        this.date = date;
        this.evidence = evidence;
        this.hash = hash;
        author = actor;
        registered = Instant.now();
    }

    Application view() {
        return new Application(date, evidence, author, registered);
    }
}
