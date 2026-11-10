package software.mspi.empresas.api;

import java.util.List;
import java.util.UUID;

/** Authorization based on the current server session and the live common registry. */
public interface CompanyAccess {

    CompanyPage listMine(int page, int size);

    CompanyMembership require(UUID companyId, CompanyPermission permission);

    ActionAssignee requireActionAssignee(UUID companyId, UUID membershipId);

    UUID currentActionMembership(UUID companyId);

    /** Supply the responsible user's ID from the persisted delivery, never from client input. */
    void requireIndependentReviewer(UUID companyId, UUID deliveryResponsibleUserId);

    record CompanyMembership(UUID empresaId, String nombre, String zonaHoraria, List<CompanyRole> roles) {
        public CompanyMembership {
            roles = List.copyOf(roles);
        }
    }

    record ActionAssignee(UUID membershipId, UUID userId, List<CompanyRole> roles) {
        public ActionAssignee {
            roles = List.copyOf(roles);
        }
    }

    record CompanyPage(List<CompanyMembership> empresas, int pagina, int tamano, boolean hayMas) {
        public CompanyPage {
            empresas = List.copyOf(empresas);
        }
    }
}
