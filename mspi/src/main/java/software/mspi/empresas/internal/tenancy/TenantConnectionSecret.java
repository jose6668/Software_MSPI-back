package software.mspi.empresas.internal.tenancy;
import java.util.regex.Pattern;

public record TenantConnectionSecret(String jdbcUrl, String username, String password) {
    private static final Pattern URL_SECRET = Pattern.compile(
            "[?&](user|password|sslpassword)=|^jdbc:postgresql://[^/]*@", Pattern.CASE_INSENSITIVE);
    public TenantConnectionSecret {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql:") || URL_SECRET.matcher(jdbcUrl).find()
                || username == null || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Se requiere configuración PostgreSQL externa válida.");
        }
    }
    @Override public String toString() { return "TenantConnectionSecret[redacted]"; }
}
