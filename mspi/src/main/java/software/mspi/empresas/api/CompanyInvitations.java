package software.mspi.empresas.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CompanyInvitations {

    Issued issue(UUID companyId, String email, List<CompanyRole> roles);

    void revoke(UUID companyId, UUID invitationId);

    Accepted accept(String token);

    Accepted enroll(String token, String displayName, String password);

    record Issued(UUID invitacionId, Instant venceEn) {}

    record Accepted(UUID empresaId, UUID membresiaId, UUID usuarioId, List<CompanyRole> roles) {
        public Accepted {
            roles = List.copyOf(roles);
        }
    }
}
