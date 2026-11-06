package software.mspi.empresas.internal.persistence.common;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "empresas", schema = "comun")
public class CompanyRegistration {
    @Id private UUID id;
    @Column(name = "esquema_interno", nullable = false) private String schemaName;
    @Column(name = "estado", nullable = false) private String state;
    @Column(name = "version_estructura") private String structureVersion;
    protected CompanyRegistration() {}
    public UUID getId() { return id; }
    public String getSchemaName() { return schemaName; }
    public String getState() { return state; }
    public String getStructureVersion() { return structureVersion; }
}
