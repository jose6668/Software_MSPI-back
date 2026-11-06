package software.mspi.empresas.internal.persistence;
import java.util.Map;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.*;
import software.mspi.empresas.internal.persistence.common.CompanyRegistryRepository;

@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(basePackageClasses = CompanyRegistryRepository.class,
        entityManagerFactoryRef = "commonEntityManagerFactory", transactionManagerRef = "commonTransactionManager")
public class CommonJpaConfiguration {
    @Bean(name = {"entityManagerFactory", "commonEntityManagerFactory"})
    @Primary
    LocalContainerEntityManagerFactoryBean commonFactory(DataSource dataSource, ResourceLoader resources) {
        return JpaFactories.create("common", "common", dataSource, resources,
                Map.of("hibernate.hbm2ddl.auto", "validate"));
    }
    @Bean(name = {"transactionManager", "commonTransactionManager"})
    @Primary
    JpaTransactionManager commonTransactions(@Qualifier("commonEntityManagerFactory") EntityManagerFactory factory) {
        return new JpaTransactionManager(factory);
    }
}
