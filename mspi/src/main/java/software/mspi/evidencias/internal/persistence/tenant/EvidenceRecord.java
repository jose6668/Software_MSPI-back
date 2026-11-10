package software.mspi.evidencias.internal.persistence.tenant;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;
import software.mspi.evidencias.api.ResponseEvidence.Metadata;
import software.mspi.evidencias.internal.persistence.tenant.JpaResponseEvidence.StoreTarget;

@Entity
@Table(name = "evidencias")
@DynamicUpdate
class EvidenceRecord {
    @Id UUID id;
    @Column(name = "empresa_id", updatable = false) UUID company;
    @Column(name = "evaluacion_id", updatable = false) UUID evaluation;
    @Column(name = "respuesta_id", updatable = false) UUID response;
    @Column(name = "implementacion_id", updatable = false) UUID implementation;
    @Column(name = "version_implementacion_id", updatable = false) UUID version;
    @Column(name = "accion_id", updatable = false) UUID action;
    @Column(name = "nombre_original", updatable = false) String name;
    @Column(name = "tipo_detectado", updatable = false) String type;
    @Column(name = "tamano_bytes", updatable = false) int size;
    @Column(name = "huella_contenido", updatable = false) String digest;
    @Column(name = "descripcion", updatable = false) String description;
    @Column(name = "estado_carga", insertable = false) String state;
    @Column(name = "estado_inspeccion") String inspection;
    @Column(name = "inspeccionada_en") Instant inspectedAt;
    @Column(name = "motor_firma") String engine;
    @Column(name = "creada_por", updatable = false) UUID author;
    @Column(name = "creada_en", updatable = false) Instant created;

    protected EvidenceRecord() {}

    EvidenceRecord(StoreTarget target, String name, String description, EvidenceContent content) {
        id = UUID.randomUUID();
        company = target.company();
        evaluation = target.evaluation();
        response = target.response();
        implementation = target.implementation();
        version = target.version();
        action = target.action();
        this.name = name;
        this.description = description;
        type = content.type();
        size = content.bytes().length;
        digest = content.digest();
        state = "PENDIENTE";
        author = target.actor();
        created = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    Metadata metadata() {
        return new Metadata(id, name, type, size, description, author, created, inspection);
    }
}
