package software.mspi.empresas.internal.tenancy;
import java.time.Clock;
import java.util.Map;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.config.BootstrapMode;
import org.springframework.orm.jpa.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import software.mspi.empresas.api.*;
import software.mspi.empresas.internal.persistence.JpaFactories;
import software.mspi.empresas.internal.persistence.common.CompanyRegistryRepository;
import software.mspi.empresas.internal.persistence.tenant.CompanyContextRepository;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
@EnableConfigurationProperties(TenancyProperties.class)
@EnableJpaRepositories(basePackageClasses = CompanyContextRepository.class,
        entityManagerFactoryRef = "tenantEntityManagerFactory", transactionManagerRef = "tenantTransactionManager",
        bootstrapMode = BootstrapMode.LAZY)
public class TenantJpaConfiguration {
    @Bean @ConditionalOnMissingBean(TenantSecretSource.class)
    TenantSecretSource tenantSecrets(Environment environment) { return new EnvironmentTenantSecretSource(environment); }

    @Bean @ConditionalOnMissingBean(TenantAccessPolicy.class)
    TenantAccessPolicy tenantAccessPolicy() {
        return (actor, company) -> { throw new AccessDeniedException("Acceso empresarial no autorizado."); };
    }

    @Bean(destroyMethod = "close")
    TenantConnectionPools tenantPools(TenancyProperties limits, TenantSecretSource secrets, ObjectProvider<Clock> clock) {
        return new TenantConnectionPools(limits, secrets, clock.getIfAvailable(Clock::systemUTC));
    }
    @Bean TenantContext tenantContext() { return new TenantContext(); }
    @Bean TenantConnectionProvider tenantConnections(DataSource common, TenantContext context) {
        return new TenantConnectionProvider(common, context);
    }
    @Bean(name = "tenantEntityManagerFactory")
    LocalContainerEntityManagerFactoryBean tenantFactory(TenantConnectionProvider provider, ResourceLoader resources) {
        return JpaFactories.create("enterprise", "tenant", null, resources,
                Map.of("hibernate.hbm2ddl.auto", "none",
                        "hibernate.multi_tenant_connection_provider", provider,
                        "hibernate.tenant_identifier_resolver", provider));
    }
    @Bean(name = "tenantTransactionManager")
    JpaTransactionManager tenantTransactions(@Qualifier("tenantEntityManagerFactory") EntityManagerFactory factory) {
        return new JpaTransactionManager(factory);
    }
    @Bean TenantUnitOfWork tenantUnitOfWork(TenantAccessPolicy policy, CompanyRegistryRepository registry,
            TenantConnectionPools pools, TenantContext context,
            @Qualifier("tenantTransactionManager") JpaTransactionManager manager) {
        return new ScopedTenantUnitOfWork(policy, registry, pools, context, new TransactionTemplate(manager));
    }
}
