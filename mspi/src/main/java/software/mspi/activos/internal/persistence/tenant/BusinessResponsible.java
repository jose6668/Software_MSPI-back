package software.mspi.activos.internal.persistence.tenant;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import software.mspi.activos.api.ProcessCatalog.Responsible;
import software.mspi.activos.api.ProcessCatalog.ResponsibleInput;
import software.mspi.activos.api.ProcessCatalog.ResponsibleType;

@Entity
@Table(name = "responsables_negocio")
class BusinessResponsible extends CatalogRecord {

    @Column(name = "tipo") @Enumerated(EnumType.STRING) ResponsibleType type;
    @Column(name = "membresia_id") UUID membershipId;

    protected BusinessResponsible() {}

    BusinessResponsible(UUID companyId) {
        super(companyId);
    }

    void update(ResponsibleInput input) {
        name = input.nombre();
        type = input.tipo();
        membershipId = input.membresiaId();
    }

    Responsible view() {
        return new Responsible(id, name, type, membershipId, state, revision);
    }
}
