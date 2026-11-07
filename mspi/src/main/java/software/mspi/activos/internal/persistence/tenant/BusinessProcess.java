package software.mspi.activos.internal.persistence.tenant;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import software.mspi.activos.api.ProcessCatalog.Process;
import software.mspi.activos.api.ProcessCatalog.ProcessInput;

@Entity
@Table(name = "procesos")
class BusinessProcess extends CatalogRecord {

    @Column(name = "descripcion") String description;
    @Column(name = "responsable_id") UUID responsibleId;

    protected BusinessProcess() {}

    BusinessProcess(UUID companyId) {
        super(companyId);
    }

    void update(ProcessInput input) {
        name = input.nombre();
        description = input.descripcion();
        responsibleId = input.responsableId();
    }

    Process view() {
        return new Process(id, name, description, responsibleId, state, revision);
    }
}
