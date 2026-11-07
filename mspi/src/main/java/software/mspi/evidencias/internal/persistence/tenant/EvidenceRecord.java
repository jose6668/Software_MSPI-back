package software.mspi.evidencias.internal.persistence.tenant;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import software.mspi.evidencias.api.ResponseEvidence.Metadata;
import software.mspi.evidencias.api.ResponseEvidence.Target;

@Entity
@Table(name = "evidencias")
class EvidenceRecord {
    @Id UUID id;
    @Column(name = "empresa_id", updatable = false) UUID company;
    @Column(name = "evaluacion_id", updatable = false) UUID evaluation;
    @Column(name = "respuesta_id", updatable = false) UUID response;
    @Column(name = "nombre_original", updatable = false) String name;
    @Column(name = "tipo_detectado", updatable = false) String type;
    @Column(name = "tamano_bytes", updatable = false) int size;
    @Column(name = "huella_contenido", updatable = false) String digest;
    @Column(name = "descripcion", updatable = false) String description;
    @Column(name = "estado_carga", insertable = false) String state;
    @Column(name = "creada_por", updatable = false) UUID author;
    @Column(name = "creada_en", updatable = false) Instant created;

    protected EvidenceRecord() {}

    EvidenceRecord(Target target, String name, String description, EvidenceContent content) {
        id = UUID.randomUUID();
        company = target.company();
        evaluation = target.evaluation();
        response = target.response();
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
        return new Metadata(id, name, type, size, description, author, created);
    }
}
