package software.mspi;
import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import software.mspi.empresas.api.TenantAccessPolicy;
import software.mspi.empresas.internal.tenancy.TenantSecretSource;

@TestConfiguration(proxyBeanMethods = false)
class TenantIsolationConfiguration {
    @Bean TenantIsolationFixture isolationFixture(TestDatabase database) throws Exception { return new TenantIsolationFixture(database); }
    @Bean @Primary TenantAccessPolicy fixturePolicy(TenantIsolationFixture fixture) { return fixture.policy(); }
    @Bean @Primary TenantSecretSource fixtureSecrets(TenantIsolationFixture fixture) { return fixture.secrets(); }
    @Bean Clock poolClock(TenantIsolationFixture fixture) { return fixture.clock; }
}
