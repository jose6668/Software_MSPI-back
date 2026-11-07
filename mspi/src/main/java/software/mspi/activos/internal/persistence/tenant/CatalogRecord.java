package software.mspi.activos.internal.persistence.tenant;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import org.springframework.dao.OptimisticLockingFailureException;
import software.mspi.activos.api.CatalogFailure;
import software.mspi.activos.api.ProcessCatalog.State;

@MappedSuperclass
abstract class CatalogRecord {

    @Id UUID id;
    @Column(name = "empresa_id", updatable = false) UUID companyId;
    @Column(name = "nombre") String name;
    @Column(name = "estado") @Enumerated(EnumType.STRING) State state;
    @Version @Column(name = "revision") long revision;

    protected CatalogRecord() {}

    CatalogRecord(UUID companyId) {
        this.id = UUID.randomUUID();
        this.companyId = companyId;
        this.state = State.ACTIVO;
    }

    void requireEditable(long expected) {
        requireRevision(expected);
        if (state != State.ACTIVO) {
            throw new CatalogFailure(CatalogFailure.Reason.ARCHIVED);
        }
    }

    void requireRevision(long expected) {
        if (revision != expected) {
            throw new OptimisticLockingFailureException("Revisión desactualizada.");
        }
    }
}
