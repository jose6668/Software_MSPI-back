package software.mspi.empresas.internal.persistence.common;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

public interface CompanyRegistryRepository extends Repository<CompanyRegistration, UUID> {
    @Transactional(readOnly = true, transactionManager = "commonTransactionManager")
    Optional<CompanyRegistration> findById(UUID id);
}
