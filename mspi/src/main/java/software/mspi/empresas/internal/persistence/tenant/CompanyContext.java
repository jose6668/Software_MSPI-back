package software.mspi.empresas.internal.persistence.tenant;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "contexto_empresa")
public class CompanyContext {
    @Id @Column(name = "empresa_id") private UUID companyId;
    protected CompanyContext() {}
    public UUID getCompanyId() { return companyId; }
}
