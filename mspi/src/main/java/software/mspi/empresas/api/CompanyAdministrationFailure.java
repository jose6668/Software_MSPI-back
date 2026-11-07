package software.mspi.empresas.api;

public final class CompanyAdministrationFailure extends RuntimeException {

    public enum Reason { MISSING, LAST_ADMINISTRATOR, REVOKED, INVALID_ZONE }

    private final Reason reason;

    public CompanyAdministrationFailure(Reason reason) {
        super("No se pudo completar la operación de administración empresarial.");
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
