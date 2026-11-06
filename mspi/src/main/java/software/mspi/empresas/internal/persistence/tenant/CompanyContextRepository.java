package software.mspi.empresas.internal.persistence.tenant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface CompanyContextRepository extends Repository<CompanyContext, UUID> {
    Optional<CompanyContext> findById(UUID id);
}
