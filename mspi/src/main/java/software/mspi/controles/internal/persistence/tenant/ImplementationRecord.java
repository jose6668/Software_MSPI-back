package software.mspi.controles.internal.persistence.tenant;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import software.mspi.controles.api.ControlImplementations.Scope;
import software.mspi.controles.api.ControlImplementations.State;

@Entity
@Table(name = "implementaciones_control")
class ImplementationRecord {
    @Id UUID id;
    @Column(name = "empresa_id", updatable = false) UUID company;
    @Column(name = "version_catalogo_id", updatable = false) UUID catalog;
    @Column(name = "elemento_id", updatable = false) UUID element;
    @Column(name = "estado") @Enumerated(EnumType.STRING) State state;
    @Column(name = "numero_version") int number;
    @Column(name = "revision") long revision;
    @Column(name = "creada_por", updatable = false) UUID author;
    @Column(name = "creada_en", updatable = false) Instant created;

    protected ImplementationRecord() {}

    ImplementationRecord(Scope scope, UUID catalog, UUID element) {
        id = UUID.randomUUID();
        company = scope.company();
        this.catalog = catalog;
        this.element = element;
        state = State.PROPUESTO;
        number = 1;
        author = scope.actor();
        created = Instant.now();
    }
}
