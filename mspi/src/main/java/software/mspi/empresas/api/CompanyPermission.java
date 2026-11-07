package software.mspi.empresas.api;

import java.util.Collection;
import java.util.Set;

import static software.mspi.empresas.api.CompanyRole.AE;
import static software.mspi.empresas.api.CompanyRole.AU;
import static software.mspi.empresas.api.CompanyRole.GS;
import static software.mspi.empresas.api.CompanyRole.RA;

/** Initial role grants. Object and transition rules must also be checked by each operation. */
public enum CompanyPermission {
    READ_CONTEXT(AE, GS, RA, AU), // PER-05/06, minimal projection
    READ_PROCESS_CATALOG(AE, GS, AU), // PER-12
    EDIT_PROCESS_CATALOG(AE, GS),     // PER-13
    READ_ASSETS(AE, GS, AU),      // PER-14
    EDIT_ASSETS(AE, GS),          // PER-15
    READ_ACTIONS(AE, GS, AU),     // PER-29, general access. Assigned RA views are separate.
    REVIEW_ACTION(AE, GS);       // PER-33, subject to the independent reviewer rule.

    private final Set<CompanyRole> grantingRoles;

    CompanyPermission(CompanyRole... roles) {
        this.grantingRoles = Set.of(roles);
    }

    public boolean grantedTo(Collection<CompanyRole> roles) {
        return roles.stream().anyMatch(grantingRoles::contains);
    }
}
