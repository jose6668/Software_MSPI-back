package software.mspi.controles.internal.persistence.tenant;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import software.mspi.controles.api.ControlImplementations.Application;
import software.mspi.controles.api.ControlImplementations.Details;
import software.mspi.controles.api.ControlImplementations.Mode;
import software.mspi.controles.api.ControlImplementations.Type;
import software.mspi.controles.api.ControlImplementations.Version;

@Entity
@Immutable
@Table(name = "versiones_implementacion_control")
class ControlVersion {
    @Id UUID id;
    @Column(name = "empresa_id") UUID company;
    @Column(name = "implementacion_id") UUID implementation;
    @Column(name = "numero") int number;
    @Column(name = "nombre") String name;
    @Column(name = "descripcion") String description;
    @Column(name = "alcance") String scope;
    @Column(name = "trabajo") String work;
    @Column(name = "responsable_id") UUID responsible;
    @Column(name = "tipo") @Enumerated(EnumType.STRING) Type type;
    @Column(name = "modo") @Enumerated(EnumType.STRING) Mode mode;
    @Column(name = "motivo_cambio") String reason;
    @Column(name = "creada_por") UUID author;
    @Column(name = "creada_en") Instant created;

    protected ControlVersion() {}

    ControlVersion(ImplementationRecord root, Details details, String reason, UUID actor) {
        id = UUID.randomUUID();
        company = root.company;
        implementation = root.id;
        number = root.number;
        name = details.nombre();
        description = details.descripcion();
        scope = details.alcance();
        work = details.trabajo();
        responsible = details.responsableId();
        type = details.tipo();
        mode = details.modo();
        this.reason = reason;
        author = actor;
        created = Instant.now();
    }

    Version view(Application application) {
        return new Version(id, number, new Details(name, description, scope, work, responsible, type, mode),
                reason, author, created, application);
    }
}
