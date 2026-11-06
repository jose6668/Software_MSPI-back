package software.mspi.empresas.internal.persistence.tenant;
import java.util.UUID;
import jakarta.persistence.*;

/** Test-only entity; never included in the production JAR. */
@Entity @Table(name = "isolation_probe")
public class IsolationProbe {
    @Id private UUID id;
    @Column(name = "empresa_id", nullable = false) private UUID companyId;
    @Column(name = "valor", nullable = false) private String value;
    @Version @Column(name = "revision") private long revision;
    protected IsolationProbe() {}
    public IsolationProbe(UUID id, UUID companyId, String value) {
        this.id = id; this.companyId = companyId; this.value = value;
    }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
