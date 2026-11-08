package software.mspi.empresas.api;

/** No credentials, company identifiers or SQL are included in the public failure. */
public final class DatabaseCapacityExceeded extends IllegalStateException {
    public DatabaseCapacityExceeded() { super("Capacidad de base de datos ocupada."); }
}
