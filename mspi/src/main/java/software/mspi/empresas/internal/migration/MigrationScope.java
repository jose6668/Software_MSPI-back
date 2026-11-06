package software.mspi.empresas.internal.migration;

import java.util.Objects;
import java.util.UUID;

/** Technical target resolved from an internal company identifier, never a free schema name. */
public final class MigrationScope {

    private final UUID companyId;

    private MigrationScope(UUID companyId) {
        this.companyId = companyId;
    }

    public static MigrationScope common() {
        return new MigrationScope(null);
    }

    public static MigrationScope tenant(UUID companyId) {
        return new MigrationScope(Objects.requireNonNull(companyId, "companyId"));
    }

    public boolean isCommon() {
        return companyId == null;
    }

    public UUID companyId() {
        return companyId;
    }

    public String schema() {
        return isCommon() ? "comun" : "emp_" + suffix();
    }

    public String migrationRole() {
        return isCommon() ? "mspi_common_migrator" : "mspi_mig_" + suffix();
    }

    public String runtimeRole() {
        return isCommon() ? "mspi_common_runtime" : "mspi_run_" + suffix();
    }

    private String suffix() {
        return companyId.toString().replace("-", "");
    }
}
