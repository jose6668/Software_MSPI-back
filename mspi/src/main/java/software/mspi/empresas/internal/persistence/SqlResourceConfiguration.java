package software.mspi.empresas.internal.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DelegatingDataSource;

@Configuration(proxyBeanMethods = false)
class SqlResourceConfiguration {
    @Bean static SqlBudget sqlBudget(Environment environment) {
        return new SqlBudget(Binder.get(environment).bind("mspi.sql-budget", SqlResourceSettings.class)
                .orElseThrow(() -> new IllegalArgumentException("Configure los presupuestos SQL.")));
    }
    @Bean static BeanPostProcessor boundedCommonDataSource(SqlBudget budget) {
        return new BeanPostProcessor() {
            @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                if (bean instanceof HikariDataSource source) {
                    if (source.isRunning()) throw new IllegalStateException("El pool debe configurarse antes de arrancar.");
                    source.setMaximumPoolSize(budget.settings().commonConnections());
                    source.setMinimumIdle(0);
                    source.setConnectionTimeout(budget.settings().commonWait().toMillis());
                    budget.configureNetwork(source);
                }
                return bean;
            }
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                return bean instanceof HikariDataSource source ? new BudgetedDataSource(source, budget) : bean;
            }
        };
    }
    private static final class BudgetedDataSource extends DelegatingDataSource implements AutoCloseable {
        private final HikariDataSource source;
        private final SqlBudget budget;
        BudgetedDataSource(HikariDataSource source, SqlBudget budget) {
            super(source); this.source = source; this.budget = budget;
        }
        @Override public Connection getConnection() throws SQLException {
            var connection = source.getConnection();
            try { budget.apply(connection); return connection; }
            catch (SQLException | RuntimeException failure) {
                source.evictConnection(connection); connection.close(); throw failure;
            }
        }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLException("Use la identidad runtime configurada.", "28000");
        }
        @Override public void close() { source.close(); }
    }
}
