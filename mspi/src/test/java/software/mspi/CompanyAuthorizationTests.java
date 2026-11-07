package software.mspi;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import software.mspi.empresas.api.CompanyAccess;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;
import software.mspi.empresas.internal.persistence.tenant.IsolationProbe;
import software.mspi.empresas.internal.tenancy.TenantSecretSource;
import software.mspi.identidad.api.SessionIdentity;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"mspi.tenancy.enabled=true", "mspi.tenancy.max-pools=2",
        "mspi.tenancy.connections-per-pool=1", "mspi.tenancy.connection-timeout=5s", "mspi.tenancy.idle-ttl=30s"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class, CompanyAuthorizationTests.Fixtures.class,
        CompanyAuthorizationTests.ProbeController.class})
class CompanyAuthorizationTests {

    private static final String MINE = "/api/v1/mis-empresas";
    private static final String CONTEXT = "/api/v1/empresas/{empresaId}/contexto";
    private static final String PROBE = "/api/v1/pruebas-autorizacion/{empresaId}";
    private static final UUID SHARED = TenantIsolationFixture.SHARED_ID;

    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired PasswordEncoder encoder;
    @Autowired TenantIsolationFixture fixture;
    @Autowired TestDelivery delivery;

    @Test
    void listsOnlyOwnOperableCompaniesAndSelectsExplicitMinimalContext() throws Exception {
        var account = account();
        member(account, a(), "RA");
        member(account, b(), "AU", "GS");
        member(account, c(), "AE");
        var session = login(account);
        fixture.metadata(fixture.third, "ACTIVA", "999");
        try {
            mvc.perform(get(MINE)).andExpect(status().isUnauthorized());
            mvc.perform(get(MINE).session(session).param("tamano", "1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.empresas.length()").value(1))
                    .andExpect(jsonPath("$.pagina").value(0)).andExpect(jsonPath("$.hayMas").value(true));
            mvc.perform(get(MINE).session(session).param("tamano", "1").param("pagina", "1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.empresas.length()").value(1))
                    .andExpect(jsonPath("$.hayMas").value(false));
            mvc.perform(get(MINE).session(session).param("tamano", "101"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDACION"));
            mvc.perform(get(CONTEXT, c()).session(session)).andExpect(status().isForbidden());
            var view = mvc.perform(get(CONTEXT, a()).session(session)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.empresaId").value(a().toString()))
                    .andExpect(jsonPath("$.roles[0]").value("RA")).andReturn();
            assertThat(mapper.readTree(view.getResponse().getContentAsByteArray()).propertyNames())
                    .containsExactlyInAnyOrder("empresaId", "nombre", "zonaHoraria", "roles");
            mvc.perform(get(CONTEXT, b()).session(session)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles[0]").value("AU"))
                    .andExpect(jsonPath("$.roles[1]").value("GS"));
            mvc.perform(get(PROBE + "/registros/{id}", a(), SHARED).session(session))
                    .andExpect(status().isForbidden());
        } finally {
            fixture.metadata(fixture.third, "ACTIVA", "1");
        }
    }

    @Test
    void grantsCombinedRolesLocallyAndRemovesEditOnNextRequest() throws Exception {
        var account = account();
        member(account, a(), "AE", "GS");
        UUID membershipB = member(account, b(), "AU");
        var session = login(account);
        read(session, a(), SHARED).andExpect(status().isOk()).andExpect(jsonPath("$.valor").value("A"));
        read(session, b(), SHARED).andExpect(status().isOk()).andExpect(jsonPath("$.valor").value("B"));
        edit(session, b(), "rejected").andExpect(status().isForbidden());

        sql("INSERT INTO comun.membresia_roles VALUES (?, 'GS')", membershipB);
        try {
            mvc.perform(put(PROBE + "/registros/{id}", b(), SHARED).session(session)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"valor\":\"sin-csrf\"}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALIDO"));
            edit(session, b(), "B-updated").andExpect(status().isNoContent());
            read(session, a(), SHARED).andExpect(status().isOk()).andExpect(jsonPath("$.valor").value("A"));
            read(session, b(), SHARED).andExpect(status().isOk()).andExpect(jsonPath("$.valor").value("B-updated"));
        } finally {
            sql("DELETE FROM comun.membresia_roles WHERE membresia_id = ? AND rol_codigo = 'GS'", membershipB);
            sql("UPDATE " + fixture.second.scope().schema() + ".isolation_probe SET valor = 'B' WHERE id = ?", SHARED);
        }
        edit(session, b(), "rejected-again").andExpect(status().isForbidden());
        read(session, b(), SHARED).andExpect(status().isOk()).andExpect(jsonPath("$.valor").value("B"));
    }

    @Test
    void rejectsForeignObjectAndReflectsSuspensionAndRevocationInTheSameSession() throws Exception {
        var account = account();
        UUID membershipB = member(account, b(), "GS");
        member(account, a(), "AE");
        var session = login(account);
        UUID onlyB = UUID.randomUUID();
        sql("INSERT INTO " + fixture.second.scope().schema()
                + ".isolation_probe (id, empresa_id, valor) VALUES (?, ?, 'privado-b')", onlyB, b());
        read(session, a(), onlyB).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_ENCONTRADO"));
        read(session, b(), onlyB).andExpect(status().isOk()).andExpect(jsonPath("$.valor").value("privado-b"));

        fixture.metadata(fixture.first, "SUSPENDIDA", "1");
        try {
            mvc.perform(get(CONTEXT, a()).session(session)).andExpect(status().isForbidden());
            read(session, a(), SHARED).andExpect(status().isForbidden());
            read(session, b(), SHARED).andExpect(status().isOk());
        } finally {
            fixture.metadata(fixture.first, "ACTIVA", "1");
        }

        sql("UPDATE comun.membresias SET estado = 'REVOCADA', revocada_en = CURRENT_TIMESTAMP WHERE id = ?", membershipB);
        read(session, b(), onlyB).andExpect(status().isForbidden());
        mvc.perform(get(MINE).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.empresas.length()").value(1))
                .andExpect(jsonPath("$.empresas[0].empresaId").value(a().toString()));

        sql("UPDATE comun.usuarios SET estado_cuenta = 'DESHABILITADA' WHERE id = ?", account.id());
        mvc.perform(get(CONTEXT, a()).session(session)).andExpect(status().isForbidden());
    }

    @Test
    void platformAttributionAndForgedActorCannotResolveTenantCredentials() throws Exception {
        var ap = account();
        var other = account();
        member(other, c(), "AE");
        sql("INSERT INTO comun.atribuciones_plataforma VALUES (?, 'AP')", ap.id());
        var session = login(ap);
        int lookups = fixture.secretLoads.get();
        mvc.perform(get(MINE).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.empresas.length()").value(0));
        mvc.perform(get(CONTEXT, c()).session(session)).andExpect(status().isForbidden());
        read(session, c(), SHARED).andExpect(status().isForbidden());
        mvc.perform(get(PROBE + "/actor/{actorId}", c(), other.id()).session(session))
                .andExpect(status().isForbidden());
        assertThat(fixture.secretLoads.get()).isEqualTo(lookups);
    }

    @Test
    void combinedRolesCannotDecideOwnDeliveryButMayReadIt() throws Exception {
        var author = account();
        var reviewer = account();
        member(author, a(), "GS", "RA");
        member(reviewer, a(), "AE");
        delivery.responsible.set(author.id());
        var authorSession = login(author);
        var reviewerSession = login(reviewer);

        mvc.perform(get(PROBE + "/entrega", a()).session(authorSession)).andExpect(status().isOk());
        for (String decision : new String[]{"aprobar", "devolver"}) {
            mvc.perform(post(PROBE + "/entrega/{decision}", a(), decision).session(authorSession).with(csrf()))
                    .andExpect(status().isForbidden());
            mvc.perform(post(PROBE + "/entrega/{decision}", a(), decision).session(reviewerSession).with(csrf()))
                    .andExpect(status().isNoContent());
        }
    }

    @Test
    void databaseEnforcesUniqueMembershipsAndNonemptyEnterpriseRoles() throws Exception {
        var account = account();
        UUID membership = member(account, a(), "AE");
        assertSqlState(() -> member(account, a(), "GS"), "23505");
        assertSqlState(() -> member(account, b()), "23514");
        assertSqlState(() -> member(account, c(), "AP"), "23503");
        assertSqlState(() -> sql("INSERT INTO comun.membresia_roles VALUES (?, 'AE')", membership), "23505");
        assertSqlState(() -> sql("DELETE FROM comun.membresia_roles WHERE membresia_id = ?", membership), "23503");
        UUID membershipB = member(account, b(), "GS");
        String schema = fixture.first.scope().schema();
        String table = schema + ".membresia_probe";
        sql("CREATE TABLE " + table + " (empresa_id uuid NOT NULL REFERENCES " + schema
                + ".contexto_empresa(empresa_id), membresia_id uuid NOT NULL,"
                + " FOREIGN KEY (empresa_id, membresia_id) REFERENCES comun.membresias(empresa_id, id))");
        sql("GRANT INSERT ON " + table + " TO " + fixture.first.scope().runtimeRole());
        try (var runtime = fixture.database.runtime(); var statement = runtime.createStatement()) {
            assertSqlState(() -> statement.executeUpdate("DELETE FROM comun.membresia_roles"), "42501");
        }
        try (var runtime = fixture.database.connect(fixture.first.scope().runtimeRole(), fixture.first.runtimePassword());
                var statement = runtime.createStatement();
                var insert = runtime.prepareStatement("INSERT INTO " + table + " VALUES (?, ?)")) {
            assertSqlState(() -> statement.executeQuery("SELECT id FROM comun.membresias"), "42501");
            insert.setObject(1, a());
            insert.setObject(2, membership);
            assertThat(insert.executeUpdate()).isEqualTo(1);
            insert.setObject(2, membershipB);
            assertSqlState(insert::executeUpdate, "23503");
        }
    }

    private UUID a() { return fixture.first.scope().companyId(); }
    private UUID b() { return fixture.second.scope().companyId(); }
    private UUID c() { return fixture.third.scope().companyId(); }

    private ResultActions read(MockHttpSession session, UUID company, UUID id) throws Exception {
        return mvc.perform(get(PROBE + "/registros/{id}", company, id).session(session));
    }

    private ResultActions edit(MockHttpSession session, UUID company, String value) throws Exception {
        return mvc.perform(put(PROBE + "/registros/{id}", company, SHARED).session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("valor", value))));
    }

    private Account account() throws SQLException {
        UUID id = UUID.randomUUID();
        String email = id + "@example.test";
        String password = UUID.randomUUID().toString();
        sql("""
                INSERT INTO comun.usuarios(id, nombre_visible, correo, clave_correo, correo_verificado_en,
                                           estado_cuenta, hash_credencial)
                VALUES (?, 'Persona ficticia', ?, ?, CURRENT_TIMESTAMP, 'HABILITADA', ?)
                """, id, email, email, encoder.encode(password));
        return new Account(id, email, password);
    }

    private MockHttpSession login(Account account) throws Exception {
        var result = mvc.perform(post("/api/v1/identidad/sesion").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("correo", account.email(), "contrasena", account.password()))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private UUID member(Account account, UUID company, String... roles) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var connection = fixture.database.admin();
                var membership = connection.prepareStatement("""
                        INSERT INTO comun.membresias (id, empresa_id, usuario_id, estado, rol_ancla)
                        VALUES (?, ?, ?, 'ACTIVA', ?)
                        """);
                var grant = connection.prepareStatement("INSERT INTO comun.membresia_roles VALUES (?, ?)")) {
            connection.setAutoCommit(false);
            membership.setObject(1, id);
            membership.setObject(2, company);
            membership.setObject(3, account.id());
            membership.setString(4, roles.length == 0 ? null : roles[0]);
            membership.executeUpdate();
            for (String role : roles) {
                grant.setObject(1, id);
                grant.setString(2, role);
                grant.executeUpdate();
            }
            connection.commit();
        }
        return id;
    }

    private void sql(String sql, Object... values) throws SQLException {
        try (var connection = fixture.database.admin(); var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private static void assertSqlState(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String state) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo(state));
    }

    record Account(UUID id, String email, String password) {}

    static class TestDelivery {
        final AtomicReference<UUID> responsible = new AtomicReference<>();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean TenantIsolationFixture probeData(TestDatabase database) throws Exception {
            return new TenantIsolationFixture(database);
        }
        @Bean @Primary TenantSecretSource probeSecrets(TenantIsolationFixture data) { return data.secrets(); }
        @Bean TestDelivery testDelivery() { return new TestDelivery(); }
    }

    @TestComponent
    @RestController
    static class ProbeController {
        private final TenantUnitOfWork work;
        private final CompanyAccess access;
        private final TestDelivery delivery;
        @PersistenceContext(unitName = "enterprise") EntityManager entityManager;

        ProbeController(TenantUnitOfWork work, CompanyAccess access, TestDelivery delivery) {
            this.work = work;
            this.access = access;
            this.delivery = delivery;
        }

        @GetMapping(PROBE + "/registros/{id}")
        Map<String, String> read(@PathVariable UUID empresaId, @PathVariable UUID id,
                                 @AuthenticationPrincipal SessionIdentity actor) {
            return work.execute(actor.usuarioId(), empresaId, CompanyPermission.READ_ASSETS,
                    () -> Map.of("valor", find(id).getValue()));
        }

        @PutMapping(PROBE + "/registros/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void edit(@PathVariable UUID empresaId, @PathVariable UUID id, @RequestBody Edit input,
                  @AuthenticationPrincipal SessionIdentity actor) {
            work.execute(actor.usuarioId(), empresaId, CompanyPermission.EDIT_ASSETS, () -> {
                find(id).setValue(input.valor());
                return null;
            });
        }

        @GetMapping(PROBE + "/actor/{actorId}")
        String forged(@PathVariable UUID empresaId, @PathVariable UUID actorId) {
            return work.execute(actorId, empresaId, CompanyPermission.READ_ASSETS, () -> "forbidden");
        }

        @GetMapping(PROBE + "/entrega")
        Map<String, String> delivery(@PathVariable UUID empresaId, @AuthenticationPrincipal SessionIdentity actor) {
            return work.execute(actor.usuarioId(), empresaId, CompanyPermission.READ_ACTIONS,
                    () -> Map.of("estado", "EN_REVISION"));
        }

        @PostMapping({PROBE + "/entrega/aprobar", PROBE + "/entrega/devolver"})
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void review(@PathVariable UUID empresaId, @AuthenticationPrincipal SessionIdentity actor) {
            work.execute(actor.usuarioId(), empresaId, CompanyPermission.REVIEW_ACTION, () -> {
                access.requireIndependentReviewer(empresaId, delivery.responsible.get());
                return null;
            });
        }

        private IsolationProbe find(UUID id) {
            var probe = entityManager.find(IsolationProbe.class, id);
            if (probe == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            return probe;
        }

        record Edit(String valor) {}
    }
}
