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
    READ_REFERENCE_CATALOGS(AE, GS, AU), // PER-18
    READ_EVALUATIONS(AE, GS, AU),        // PER-19
    EDIT_EVALUATION_DRAFT(AE, GS),       // PER-20
    READ_CONTROLS(AE, GS, AU),           // PER-23/26, operational consultation
    EDIT_CONTROLS(AE, GS),              // PER-26
    READ_RISKS(AE, GS, AU),             // PER-23
    EDIT_RISKS(AE, GS),                 // PER-24/25
    ACCEPT_RISK(AE),                    // PER-27
    TRANSITION_RISK(AE, GS),            // PER-28
    READ_PANEL(AE, GS, AU),             // PER-36
    EDIT_PROCESS_CATALOG(AE, GS),     // PER-13
    READ_ASSETS(AE, GS, AU),      // PER-14
    EDIT_ASSETS(AE, GS),          // PER-15
    CLASSIFY_ASSETS(AE, GS),      // PER-16
    CHANGE_ASSET_STATE(AE, GS),   // PER-17
    READ_ASSET_HISTORY(AE),       // PER-38
    READ_ACTIONS(AE, GS, AU),     // PER-29, general access. Assigned RA views are separate.
    READ_ASSIGNED_ACTIONS(AE, GS, RA, AU), // PER-29, records are filtered by the current assignment.
    EDIT_ACTIONS(AE, GS), // PER-30/31
    EXECUTE_ACTION(AE, GS, RA), // PER-32/34, subject to current assignment
    REVIEW_ACTION(AE, GS);       // PER-33, subject to the independent reviewer rule.

    private final Set<CompanyRole> grantingRoles;

    CompanyPermission(CompanyRole... roles) {
        this.grantingRoles = Set.of(roles);
    }

    public boolean grantedTo(Collection<CompanyRole> roles) {
        return roles.stream().anyMatch(grantingRoles::contains);
    }
}
