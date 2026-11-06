package software.mspi.empresas.internal.persistence;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import jakarta.persistence.SharedCacheMode;
import org.springframework.core.io.ResourceLoader;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypesScanner;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

public final class JpaFactories {
    private JpaFactories() {}
    public static LocalContainerEntityManagerFactoryBean create(String unit, String storage,
            DataSource dataSource, ResourceLoader resources, Map<String, Object> settings) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setPersistenceUnitName(unit);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setManagedTypes(new PersistenceManagedTypesScanner(resources,
                name -> name.contains(".internal.persistence." + storage + ".")).scan("software.mspi"));
        if (dataSource != null) factory.setDataSource(dataSource);
        factory.setSharedCacheMode(SharedCacheMode.NONE);
        var properties = new HashMap<String, Object>();
        properties.put("hibernate.cache.use_second_level_cache", false);
        properties.put("hibernate.cache.use_query_cache", false);
        properties.putAll(settings);
        factory.setJpaPropertyMap(properties);
        return factory;
    }
}
