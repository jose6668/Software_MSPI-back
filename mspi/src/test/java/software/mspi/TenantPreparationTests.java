package software.mspi;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;
import software.mspi.empresas.internal.migration.MigrationCli;
import software.mspi.empresas.internal.persistence.tenant.IsolationProbe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"mspi.tenancy.enabled=true", "mspi.tenancy.max-pools=2",
        "mspi.tenancy.connections-per-pool=1", "mspi.tenancy.connection-timeout=5s", "mspi.tenancy.idle-ttl=30s"})
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class, TenantIsolationConfiguration.class})
class TenantPreparationTests {

    @Autowired TestDatabase database;
    @Autowired TenantIsolationFixture fixture;
    @Autowired TenantUnitOfWork work;
    @PersistenceContext(unitName = "enterprise") EntityManager entityManager;

    @Test
    void recoversFromBootstrapConflictAndResumesWithSameCompanyAndResources() throws Exception {
        var tenant = database.reserveTenant();
        String schema = tenant.scope().schema();
        String migrationRole = tenant.scope().migrationRole();
        execute("CREATE ROLE " + migrationRole + " NOLOGIN");
        try {
            assertThat(prepare(tenant, false)).isEqualTo(1);
            assertThat(attempts(tenant)).containsExactly("9/APROVISIONAR/FALLIDO");
            assertThat(registry(tenant)).isEqualTo("ERROR_APROVISIONAMIENTO/-");
            assertThat(scalar("SELECT to_regnamespace('" + schema + "')")).isNull();
            assertThat(scalar("SELECT to_regrole('" + tenant.scope().runtimeRole() + "')")).isNull();
        } finally {
            execute("DROP ROLE " + migrationRole);
        }

        assertThat(prepare(tenant, false)).isEqualTo(3);
        assertThat(attempts(tenant)).containsExactly("9/APROVISIONAR/FALLIDO", "9/CREDENCIALES/ESPERA_CREDENCIALES");
        Object schemaOid = scalar("SELECT oid FROM pg_namespace WHERE nspname = '" + schema + "'");
        Object migratorOid = scalar("SELECT oid FROM pg_roles WHERE rolname = '" + migrationRole + "'");
        database.setTenantPasswords(tenant);
        assertThat(prepare(tenant, true)).isZero();
        assertThat(prepare(tenant, true)).isZero();

        assertThat(scalar("SELECT oid FROM pg_namespace WHERE nspname = '" + schema + "'")).isEqualTo(schemaOid);
        assertThat(scalar("SELECT oid FROM pg_roles WHERE rolname = '" + migrationRole + "'")).isEqualTo(migratorOid);
        assertThat(scalar("SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE success")).isEqualTo(9L);
        assertThat(registry(tenant)).isEqualTo("EN_PREPARACION/9");
        assertThat(attempts(tenant)).containsExactly("9/APROVISIONAR/FALLIDO", "9/CREDENCIALES/ESPERA_CREDENCIALES",
                "9/VERIFICAR/VERIFICADO", "9/VERIFICAR/VERIFICADO");
    }

    @Test
    void recordsFlywayFailureAndRetriesWithoutExposingOrAffectingCompatibleCompany() throws Exception {
        var tenant = database.reserveAndProvisionTenant();
        String migrator = tenant.scope().migrationRole();
        fixture.grants.get(fixture.actor).add(tenant.scope().companyId());
        execute("REVOKE REFERENCES (id) ON comun.empresas FROM " + migrator);
        try {
            assertThat(prepare(tenant, true)).isEqualTo(1);
            assertThat(registry(tenant)).isEqualTo("ERROR_APROVISIONAMIENTO/-");
            assertThat(attempts(tenant)).containsExactly("9/MIGRAR/FALLIDO");
            assertThat(scalar("SELECT to_regclass('" + tenant.scope().schema() + ".contexto_empresa')")).isNull();
            assertBlocked(tenant);
            assertThat(readCompatibleCompany()).isEqualTo("B");
        } finally {
            execute("GRANT REFERENCES (id) ON comun.empresas TO " + migrator);
        }

        assertThat(prepare(tenant, true)).isZero();
        assertThat(registry(tenant)).isEqualTo("EN_PREPARACION/9");
        assertThat(attempts(tenant)).containsExactly("9/MIGRAR/FALLIDO", "9/VERIFICAR/VERIFICADO");
        assertBlocked(tenant);
        assertThat(readCompatibleCompany()).isEqualTo("B");
    }

    @Test
    void withholdsInstalledVersionUntilRuntimePermissionsPassVerification() throws Exception {
        var tenant = database.reserveAndProvisionTenant();
        database.tenantFlyway(tenant).migrate();
        String ownerTable = tenant.scope().schema() + ".contexto_empresa";
        String runtime = tenant.scope().runtimeRole();
        execute("GRANT UPDATE (unica_fila) ON " + ownerTable + " TO " + runtime);
        try {
            assertThat(prepare(tenant, true)).isEqualTo(1);
            assertThat(registry(tenant)).isEqualTo("ERROR_APROVISIONAMIENTO/-");
            assertThat(attempts(tenant)).containsExactly("9/VERIFICAR/FALLIDO");
        } finally {
            execute("REVOKE UPDATE (unica_fila) ON " + ownerTable + " FROM " + runtime);
        }
        assertThat(prepare(tenant, true)).isZero();
        assertThat(registry(tenant)).isEqualTo("EN_PREPARACION/9");
        assertThat(attempts(tenant)).containsExactly("9/VERIFICAR/FALLIDO", "9/VERIFICAR/VERIFICADO");
    }

    @Test
    void resumesAnInterruptedAttemptAndKeepsItsJournalPrivate() throws Exception {
        var tenant = database.reserveAndProvisionTenant();
        execute("INSERT INTO comun.intentos_preparacion(id, empresa_id, version_objetivo, paso, resultado) VALUES ('"
                + UUID.randomUUID() + "', '" + tenant.scope().companyId() + "', '1', 'MIGRAR', 'EN_CURSO')");
        assertThat(prepare(tenant, true)).isZero();
        assertThat(attempts(tenant)).containsExactly("1/MIGRAR/INTERRUMPIDO", "9/VERIFICAR/VERIFICADO");

        try (var connection = database.runtime(); var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeQuery("SELECT * FROM comun.intentos_preparacion"))
                    .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    void serializesDifferentCompaniesAndRefusesToPrepareAnActiveCompany() throws Exception {
        var pending = database.reserveTenant();
        try (var connection = database.admin(); var statement = connection.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(1297305673, 5)");
            assertThat(prepare(pending, false)).isEqualTo(1);
            assertThat(attempts(pending)).isEmpty();
            assertThat(run("tenant", fixture.second, credentials(fixture.second))).isEqualTo(1);
            assertThat(readCompatibleCompany()).isEqualTo("B");
        }
        assertThat(prepare(pending, false)).isEqualTo(3);
        assertThat(prepare(fixture.second, true)).isEqualTo(1);
        assertThat(attempts(fixture.second)).isEmpty();
        assertThat(registry(fixture.second)).isEqualTo("ACTIVA/9");
        assertThat(readCompatibleCompany()).isEqualTo("B");
    }

    @Test
    void upgradesAnExistingSuspendedV4CompanyAndPublishesOnlyAfterVerification() throws Exception {
        var tenant = database.reserveAndProvisionTenant();
        var prior = org.flywaydb.core.Flyway.configure().configuration(database.tenantFlyway(tenant).getConfiguration())
                .target("4").load();
        assertThat(prior.migrate().migrationsExecuted).isEqualTo(4);
        String schema = tenant.scope().schema();
        String company = tenant.scope().companyId().toString();
        UUID process = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        execute("INSERT INTO " + schema + ".procesos (id, empresa_id, nombre, estado, revision) VALUES ('"
                + process + "', '" + company + "', 'Proceso previo', 'ACTIVO', 0)");
        execute("INSERT INTO " + schema + ".responsables_negocio (id, empresa_id, nombre, tipo, estado, revision) VALUES ('"
                + owner + "', '" + company + "', 'Responsable previo', 'CARGO', 'ACTIVO', 0)");
        execute("""
                INSERT INTO %s.activos (id, empresa_id, codigo, codigo_normalizado, nombre, descripcion,
                    tipo, proceso_id, propietario_id, estado, revision)
                VALUES ('%s', '%s', 'V3', 'V3', 'Activo previo', 'Inventario', 'RED', '%s', '%s', 'ACTIVO', 0)
                """.formatted(schema, UUID.randomUUID(), company, process, owner));
        execute("UPDATE comun.empresas SET estado = 'ACTIVA', version_estructura = '4' WHERE id = '" + company + "'");
        var environment = new HashMap<>(database.preparationEnvironment());
        environment.putAll(credentials(tenant));
        assertThat(run("upgrade", tenant, environment)).isEqualTo(1);
        assertThat(registry(tenant)).isEqualTo("ACTIVA/4");
        assertThat(attempts(tenant)).isEmpty();

        execute("UPDATE comun.empresas SET estado = 'SUSPENDIDA' WHERE id = '" + company + "'");
        var invalid = new HashMap<>(environment);
        invalid.put("MSPI_PREPARATION_RUNTIME_PASSWORD", UUID.randomUUID().toString());
        assertThat(run("upgrade", tenant, invalid)).isEqualTo(1);
        assertThat(registry(tenant)).isEqualTo("SUSPENDIDA/-");
        assertThat(attempts(tenant)).containsExactly("9/VERIFICAR/FALLIDO");
        assertThat(run("upgrade", tenant, environment)).isZero();
        assertThat(registry(tenant)).isEqualTo("SUSPENDIDA/9");
        assertThat(attempts(tenant)).containsExactly("9/VERIFICAR/FALLIDO", "9/VERIFICAR/VERIFICADO");
        assertThat(scalar("SELECT empresa_id FROM " + schema + ".contexto_empresa")).isEqualTo(tenant.scope().companyId());
        assertThat(scalar("SELECT nombre FROM " + schema + ".procesos")).isEqualTo("Proceso previo");
        assertThat(scalar("SELECT nombre FROM " + schema + ".activos WHERE valoracion_vigente_id IS NULL"))
                .isEqualTo("Activo previo");
        assertThat(scalar("SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE success")).isEqualTo(9L);
    }

    private void assertBlocked(TestDatabase.Tenant tenant) {
        int credentialLoads = fixture.secretLoads.get();
        assertThatThrownBy(() -> work.execute(fixture.actor, tenant.scope().companyId(), CompanyPermission.READ_CONTEXT,
                () -> "invalid"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(fixture.secretLoads.get()).isEqualTo(credentialLoads);
    }

    private String readCompatibleCompany() {
        return work.execute(fixture.actor, fixture.second.scope().companyId(), CompanyPermission.READ_CONTEXT,
                () -> entityManager.find(IsolationProbe.class, TenantIsolationFixture.SHARED_ID).getValue());
    }

    private int prepare(TestDatabase.Tenant tenant, boolean withCredentials) {
        Map<String, String> environment = new HashMap<>(database.preparationEnvironment());
        if (withCredentials) {
            environment.putAll(credentials(tenant));
        }
        return run("prepare", tenant, environment);
    }

    private Map<String, String> credentials(TestDatabase.Tenant tenant) {
        return Map.of("MSPI_MIGRATION_JDBC_URL", database.url(),
                "MSPI_MIGRATION_USER", tenant.scope().migrationRole(),
                "MSPI_MIGRATION_PASSWORD", tenant.migrationPassword(),
                "MSPI_PREPARATION_RUNTIME_PASSWORD", tenant.runtimePassword());
    }

    private int run(String command, TestDatabase.Tenant tenant, Map<String, String> environment) {
        var log = new ByteArrayOutputStream();
        try (var stream = new PrintStream(log, true, StandardCharsets.UTF_8)) {
            int code = MigrationCli.run(new String[]{command, tenant.scope().companyId().toString()},
                    environment, stream, stream);
            assertThat(log.toString(StandardCharsets.UTF_8))
                    .doesNotContain(tenant.migrationPassword(), tenant.runtimePassword());
            return code;
        }
    }

    private void execute(String sql) throws SQLException {
        try (var connection = database.admin(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Object scalar(String sql) throws SQLException {
        try (var connection = database.admin(); var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getObject(1);
        }
    }

    private String registry(TestDatabase.Tenant tenant) throws SQLException {
        return (String) scalar("SELECT estado || '/' || coalesce(version_estructura, '-') FROM comun.empresas WHERE id = '"
                + tenant.scope().companyId() + "'");
    }

    private List<String> attempts(TestDatabase.Tenant tenant) throws SQLException {
        try (var connection = database.admin(); var statement = connection.prepareStatement("""
                SELECT version_objetivo || '/' || paso || '/' || resultado FROM comun.intentos_preparacion
                WHERE empresa_id = ? ORDER BY iniciado_en
                """)) {
            statement.setObject(1, tenant.scope().companyId());
            try (var rows = statement.executeQuery()) {
                var results = new ArrayList<String>();
                while (rows.next()) {
                    results.add(rows.getString(1));
                }
                return results;
            }
        }
    }
}
