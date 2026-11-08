package software.mspi;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import javax.sql.DataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;
import software.mspi.empresas.internal.tenancy.TenantConnectionPools;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"mspi.sql-budget.statement-timeout=500ms", "mspi.sql-budget.lock-timeout=100ms",
        "mspi.sql-budget.idle-transaction-timeout=1s", "mspi.sql-budget.transaction-timeout=2s",
        "mspi.sql-budget.work-mem-kib=64", "mspi.sql-budget.common-connections=2", "mspi.sql-budget.common-wait=300ms",
        "mspi.tenancy.enabled=true", "mspi.tenancy.max-pools=2", "mspi.tenancy.connections-per-pool=1",
        "mspi.tenancy.connection-timeout=500ms", "mspi.tenancy.idle-ttl=30s"})
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class, TenantIsolationConfiguration.class})
class SqlResourceIntegrationTests {
    @Autowired DataSource source;
    @Autowired TestDatabase database;
    @Autowired TenantUnitOfWork work;
    @Autowired TenantConnectionPools pools;
    @Autowired TenantIsolationFixture fixture;
    @PersistenceContext(unitName = "enterprise") EntityManager enterprise;

    @Test void commonCancelsSlowSqlAndRemainsUsable() throws Exception {
        try (var connection = source.getConnection()) {
            assertState(() -> query(connection, "SELECT pg_sleep(2)"), "57014");
            assertThat(query(connection, "SELECT 1")).isEqualTo("1");
        }
    }
    @Test void returnsCommonConnectionWithTheBudgetRestored() throws Exception {
        String pid;
        try (var connection = source.getConnection()) {
            pid = query(connection, "SELECT pg_backend_pid()");
            query(connection, "SELECT set_config('statement_timeout','0',false), set_config('work_mem','64MB',false)");
        }
        try (var connection = source.getConnection()) {
            assertThat(query(connection, "SELECT pg_backend_pid()")).isEqualTo(pid);
            assertThat(query(connection, "SHOW statement_timeout")).isEqualTo("500ms");
            assertThat(query(connection, "SHOW work_mem")).isEqualTo("64kB");
        }
    }
    @Test void fullCommonPoolFailsWithinItsWaitAndRecoversAfterReturn() throws Exception {
        try (var first = source.getConnection(); var second = source.getConnection()) {
            long start = System.nanoTime();
            assertThatThrownBy(source::getConnection).isInstanceOf(SQLTransientConnectionException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        }
        try (var restored = source.getConnection()) { assertThat(query(restored, "SELECT 1")).isEqualTo("1"); }
    }
    @Test void lockWaitIsCancelledBeforeStatementDeadline() throws Exception {
        try (var holder = database.admin(); var blocked = source.getConnection()) {
            query(holder, "SELECT pg_advisory_lock(7341, 91)");
            try { assertState(() -> query(blocked, "SELECT pg_advisory_lock(7341, 91)"), "55P03"); }
            finally { query(holder, "SELECT pg_advisory_unlock(7341, 91)"); }
            assertThat(query(blocked, "SELECT 1")).isEqualTo("1");
        }
    }
    @Test void idleTransactionIsTerminatedAndPoolRecovers() throws Exception {
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            query(connection, "SELECT 1");
            Thread.sleep(1600);
            assertState(() -> query(connection, "SELECT 1"), "25P03");
        }
        try (var recovered = source.getConnection()) { assertThat(query(recovered, "SELECT 1")).isEqualTo("1"); }
    }
    @Test void manyShortStatementsCannotKeepATransactionOpenForever() throws Exception {
        boolean cancelled = false;
        try (var connection = source.getConnection()) {
            connection.setAutoCommit(false);
            long start = System.nanoTime();
            while (System.nanoTime() - start < Duration.ofSeconds(4).toNanos()) {
                try { query(connection, "SELECT 1"); }
                catch (SQLException error) { assertThat(error.getSQLState()).isEqualTo("25P04"); cancelled = true; break; }
                Thread.sleep(100);
            }
        }
        assertThat(cancelled).isTrue();
        try (var recovered = source.getConnection()) { assertThat(query(recovered, "SELECT 1")).isEqualTo("1"); }
    }
    @Test void tenantResetRestoresBudgetAndCancellationDoesNotPoisonOtherCompany() {
        var a = fixture.first.scope().companyId();
        var b = fixture.second.scope().companyId();
        work.execute(fixture.actor, a, CompanyPermission.READ_CONTEXT, () -> {
            assertThat(enterprise.createNativeQuery("SHOW statement_timeout").getSingleResult()).isEqualTo("500ms");
            enterprise.createNativeQuery("SET statement_timeout='0'").executeUpdate();
            return null;
        });
        assertThatThrownBy(() -> work.execute(fixture.actor, a, CompanyPermission.READ_CONTEXT, () ->
                enterprise.createNativeQuery("SELECT 1 FROM pg_sleep(2)").getSingleResult()))
                .satisfies(error -> assertThat(sqlCause(error).getSQLState()).isEqualTo("57014"));
        for (var company : new java.util.UUID[]{a,b}) {
            assertThat(work.execute(fixture.actor, company, CompanyPermission.READ_CONTEXT, () ->
                    enterprise.createNativeQuery("SHOW statement_timeout").getSingleResult())).isEqualTo("500ms");
        }
        assertThat(pools.activeUnits()).isZero();
    }
    @Test void runtimeCannotDisableSpillLimitAndSortIsCancelledWhenItExceedsIt() throws Exception {
        try (var runtime = database.runtime()) { assertState(() -> query(runtime, "SET temp_file_limit='-1'"), "42501"); }
        try (var admin = database.admin()) {
            query(admin, "ALTER ROLE mspi_common_runtime IN DATABASE " + databaseName(admin) + " SET temp_file_limit='128kB'");
            try (var runtime = database.runtime()) {
                query(runtime, "SET work_mem='64kB'");
                assertState(() -> query(runtime, "SELECT repeat(md5(i::text), 4) FROM generate_series(1,50000) g(i) ORDER BY 1"), "53400");
            } finally {
                query(admin, "ALTER ROLE mspi_common_runtime IN DATABASE " + databaseName(admin) + " SET temp_file_limit='64MB'");
            }
        }
    }
    private String databaseName(Connection connection) throws SQLException {
        return query(connection, "SELECT quote_ident(current_database())");
    }
    private String query(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            if (!statement.execute(sql)) return "";
            try (var result = statement.getResultSet()) { return result.next() ? result.getString(1) : ""; }
        }
    }
    private void assertState(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation, String state) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(SQLException.class,
                error -> assertThat(error.getSQLState()).isEqualTo(state));
    }
    private SQLException sqlCause(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) if (cause instanceof SQLException sql) return sql;
        throw new AssertionError("No se encontro causa SQL");
    }
}
