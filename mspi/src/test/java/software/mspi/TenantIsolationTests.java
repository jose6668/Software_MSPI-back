package software.mspi;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import jakarta.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;
import software.mspi.empresas.internal.persistence.common.CompanyRegistration;
import software.mspi.empresas.internal.persistence.tenant.*;
import software.mspi.empresas.internal.tenancy.TenantConnectionPools;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"mspi.tenancy.enabled=true", "mspi.tenancy.max-pools=2",
        "mspi.tenancy.connections-per-pool=1", "mspi.tenancy.connection-timeout=5s", "mspi.tenancy.idle-ttl=30s"})
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class, TenantIsolationConfiguration.class})
class TenantIsolationTests {
    @Autowired TenantUnitOfWork work;
    @Autowired TenantConnectionPools pools;
    @Autowired TenantIsolationFixture fixture;
    @Autowired CompanyContextRepository contexts;
    @Autowired @Qualifier("commonEntityManagerFactory") EntityManagerFactory common;
    @Autowired @Qualifier("tenantEntityManagerFactory") EntityManagerFactory enterprise;
    @PersistenceContext(unitName = "enterprise") EntityManager entityManager;

    private UUID a() { return fixture.first.scope().companyId(); }
    private UUID b() { return fixture.second.scope().companyId(); }
    private UUID c() { return fixture.third.scope().companyId(); }
    private <T> T execute(UUID actor, UUID company, Supplier<T> operation) {
        return work.execute(actor, company, CompanyPermission.READ_CONTEXT, operation);
    }
    private SimpleJpaRepository<IsolationProbe, UUID> repository() {
        return new SimpleJpaRepository<>(IsolationProbe.class, entityManager);
    }
    private String value(UUID company) {
        return execute(fixture.actor, company,
                () -> repository().findById(TenantIsolationFixture.SHARED_ID).orElseThrow().getValue());
    }
    private int pid(UUID company) {
        return execute(fixture.actor, company,
                () -> ((Number) entityManager.createNativeQuery("select pg_backend_pid()").getSingleResult()).intValue());
    }

    @Test void rejectsUnauthorizedAccessBeforeResolvingCredentialsAndUnscopedJpa() {
        int loads = fixture.secretLoads.get();
        var called = new AtomicBoolean();
        assertThatThrownBy(() -> execute(fixture.otherActor, b(), () -> { called.set(true); return "invalid"; }))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(called).isFalse();
        assertThat(fixture.secretLoads.get()).isEqualTo(loads);
        assertThatThrownBy(enterprise::createEntityManager).isInstanceOf(RuntimeException.class);
        assertThat(pools.activeUnits()).isZero();
    }

    @Test void alternatesIdenticalIdsWithoutPersistenceCacheOrConnectionMixing() {
        int firstPid = pid(a());
        int secondPid = pid(b());
        assertThat(firstPid).isNotEqualTo(secondPid);
        for (int i = 0; i < 10; i++) {
            assertThat(value(a())).isEqualTo("A");
            assertThat(value(b())).isEqualTo("B");
            assertThat(pid(a())).isEqualTo(firstPid);
            assertThat(pid(b())).isEqualTo(secondPid);
        }
        assertThat(pools.poolCount()).isLessThanOrEqualTo(2);
    }

    @Test void rollsBackWritesAfterExceptionsAndKeepsNextCompanyClean() {
        assertThatThrownBy(() -> execute(fixture.actor, a(), () -> {
            var row = repository().findById(TenantIsolationFixture.SHARED_ID).orElseThrow();
            row.setValue("must-rollback");
            entityManager.flush();
            throw new IllegalArgumentException("expected failure");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(value(a())).isEqualTo("A");
        assertThat(value(b())).isEqualTo("B");
        assertThat(pools.activeUnits()).isZero();
    }

    @Test void rejectsCompanyOrActorChangeAndJoinsOnlyTheSameUnit() {
        assertThatThrownBy(() -> execute(fixture.actor, a(), () -> {
            repository().findById(TenantIsolationFixture.SHARED_ID).orElseThrow().setValue("must-rollback");
            try { execute(fixture.actor, b(), () -> "forbidden"); } catch (IllegalStateException ignored) {}
            return "cannot-commit";
        })).isInstanceOf(IllegalStateException.class);
        assertThat(value(a())).isEqualTo("A");
        assertThatThrownBy(() -> execute(fixture.actor, a(),
                () -> execute(fixture.otherActor, a(), () -> "forbidden"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> execute(fixture.actor, a(),
                () -> work.execute(fixture.actor, a(), CompanyPermission.EDIT_ASSETS, () -> "forbidden")))
                .isInstanceOf(IllegalStateException.class);
        execute(fixture.actor, a(), () -> {
            int current = ((Number) entityManager.createNativeQuery("select pg_backend_pid()").getSingleResult()).intValue();
            assertThat(pid(a())).isEqualTo(current);
            return null;
        });
        assertThat(value(b())).isEqualTo("B");
    }

    @Test void keepsCommonAndTenantMappingsAndCredentialsSeparate() {
        try (var em = common.createEntityManager()) {
            assertThatThrownBy(() -> em.find(CompanyContext.class, a())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> em.createNativeQuery("select * from " + fixture.first.scope().schema() + ".contexto_empresa")
                    .getResultList()).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> execute(fixture.actor, a(), () -> {
            entityManager.find(CompanyRegistration.class, a());
            return null;
        })).isInstanceOf(IllegalArgumentException.class);
        execute(fixture.actor, a(), () -> {
            assertThat(contexts.findById(a())).isPresent();
            return null;
        });
    }

    @Test void databaseRejectsForeignIdsAndQualifiedCrossCompanySql() {
        assertThatThrownBy(() -> execute(fixture.actor, a(), () -> {
            entityManager.createNativeQuery("select * from " + fixture.second.scope().schema() + ".isolation_probe").getResultList();
            return null;
        })).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> execute(fixture.actor, a(), () -> {
            repository().saveAndFlush(new IsolationProbe(UUID.randomUUID(), b(), "foreign"));
            return null;
        })).isInstanceOf(RuntimeException.class);
        assertThat(value(a())).isEqualTo("A");
        assertThat(value(b())).isEqualTo("B");
    }

    @Test void cleansNativeSessionStateBeforeReusingThePool() {
        int backend = pid(a());
        execute(fixture.actor, a(), () -> {
            entityManager.createNativeQuery("set search_path = public").executeUpdate();
            return null;
        });
        assertThat(pid(a())).isEqualTo(backend);
        assertThat(value(a())).isEqualTo("A");
        assertThat(value(b())).isEqualTo("B");
    }

    @Test void rechecksSuspensionVersionAndAccessOnEachNewUnit() throws Exception {
        try {
            fixture.metadata(fixture.first, "SUSPENDIDA", "7");
            assertThatThrownBy(() -> value(a())).isInstanceOf(AccessDeniedException.class);
            fixture.metadata(fixture.first, "ACTIVA", "999");
            assertThatThrownBy(() -> value(a())).isInstanceOf(AccessDeniedException.class);
            fixture.metadata(fixture.first, "ACTIVA", "7");
            fixture.grants.get(fixture.actor).remove(a());
            assertThatThrownBy(() -> value(a())).isInstanceOf(AccessDeniedException.class);
        } finally {
            fixture.metadata(fixture.first, "ACTIVA", "7");
            fixture.grants.get(fixture.actor).add(a());
        }
        assertThat(value(a())).isEqualTo("A");
    }

    @Test void supportsConcurrentCompaniesWithoutThreadContextPropagation() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { for (int i=0; i<5; i++) assertThat(value(a())).isEqualTo("A"); });
            var second = executor.submit(() -> { for (int i=0; i<5; i++) assertThat(value(b())).isEqualTo("B"); });
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
            assertThatThrownBy(enterprise::createEntityManager).isInstanceOf(RuntimeException.class);
        } finally { executor.shutdownNow(); }
        assertThat(pools.activeUnits()).isZero();
    }

    @Test void boundsPoolsWithoutEvictingActiveUnitsAndEvictsIdleOnDemand() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> hold(a(), ready, release));
            var second = executor.submit(() -> hold(b(), ready, release));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(pools.activeUnits()).isEqualTo(2);
            assertThatThrownBy(() -> value(c())).isInstanceOf(IllegalStateException.class);
            assertThat(pools.poolCount()).isEqualTo(2);
            release.countDown();
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            assertThat(value(c())).isEqualTo("C");
        } finally { release.countDown(); executor.shutdownNow(); }
        assertThat(pools.poolCount()).isLessThanOrEqualTo(2);
    }

    @Test void expiresInactivePoolsWithAnExplicitClock() {
        int before = pid(a());
        fixture.clock.advance(Duration.ofSeconds(31));
        assertThat(value(a())).isEqualTo("A");
        assertThat(pid(a())).isNotEqualTo(before);
    }

    @Test void commitsOnlyToTheSelectedCompany() {
        try {
            execute(fixture.actor, a(), () -> {
                repository().findById(TenantIsolationFixture.SHARED_ID).orElseThrow().setValue("A-updated");
                return null;
            });
            assertThat(value(a())).isEqualTo("A-updated");
            assertThat(value(b())).isEqualTo("B");
        } finally {
            execute(fixture.actor, a(), () -> {
                repository().findById(TenantIsolationFixture.SHARED_ID).orElseThrow().setValue("A");
                return null;
            });
        }
    }

    @Test void rejectsWrongOrMissingRuntimeSecretsBeforeCreatingAPool() throws Exception {
        var tenant = fixture.create("secret-test", true);
        String prefix = "mspi.tenancy.connections." + tenant.scope().companyId().toString().replace("-", "") + ".";
        int count = pools.poolCount();
        fixture.environment.setProperty(prefix + "username", "mspi_common_runtime");
        assertThatThrownBy(() -> value(tenant.scope().companyId())).isInstanceOf(IllegalArgumentException.class);
        fixture.environment.setProperty(prefix + "username", tenant.scope().runtimeRole());
        fixture.environment.setProperty(prefix + "password", "");
        assertThatThrownBy(() -> value(tenant.scope().companyId())).isInstanceOf(IllegalArgumentException.class);
        assertThat(pools.poolCount()).isEqualTo(count);
        assertThat(pools.activeUnits()).isZero();
    }

    @Test void rejectsMissingOwnerBeforeExecutingBusinessWorkAndRecoversAfterFailure() throws Exception {
        var invalid = fixture.create("unused", false);
        try (var connection = fixture.database.admin(); var statement = connection.createStatement()) {
            // Deliberately corrupt a disposable schema to exercise the runtime owner check.
            statement.execute("DELETE FROM " + invalid.scope().schema() + ".uso_evidencias");
            statement.execute("DELETE FROM " + invalid.scope().schema() + ".contexto_empresa");
        }
        var called = new AtomicBoolean();
        assertThatThrownBy(() -> execute(fixture.actor, invalid.scope().companyId(),
                () -> { called.set(true); return "invalid"; })).isInstanceOf(RuntimeException.class);
        assertThat(called).isFalse();
        assertThat(value(b())).isEqualTo("B");
        assertThat(pools.activeUnits()).isZero();
    }

    private String hold(UUID company, CountDownLatch ready, CountDownLatch release) {
        return execute(fixture.actor, company, () -> {
            assertThat(repository().findById(TenantIsolationFixture.SHARED_ID)).isPresent();
            ready.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            return "done";
        });
    }
}
