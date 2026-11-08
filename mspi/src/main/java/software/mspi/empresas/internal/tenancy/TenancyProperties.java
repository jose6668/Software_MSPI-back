package software.mspi.empresas.internal.tenancy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** No production limits are inferred: enabling tenancy requires explicit configuration. */
@ConfigurationProperties("mspi.tenancy")
public record TenancyProperties(int maxPools, int connectionsPerPool, Duration connectionTimeout, Duration idleTtl) {
    public TenancyProperties {
        if (maxPools < 1 || maxPools > 4 || connectionsPerPool < 1 || connectionsPerPool > 2 || connectionTimeout == null
                || connectionTimeout.toMillis() < 250 || connectionTimeout.toMillis() > 5000 || idleTtl == null || idleTtl.isZero() || idleTtl.isNegative()) {
            throw new IllegalArgumentException("Configure límites positivos y timeout de conexión de al menos 250 ms.");
        }
    }
}
