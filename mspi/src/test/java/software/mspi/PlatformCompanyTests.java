package software.mspi;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import software.mspi.empresas.internal.migration.MigrationCli;
import software.mspi.empresas.internal.migration.MigrationScope;
import software.mspi.empresas.internal.tenancy.TenantConnectionSecret;
import software.mspi.empresas.internal.tenancy.TenantSecretSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"mspi.invitations.enabled=true", "mspi.invitations.validity=72h",
        "mspi.invitations.acceptance-url=https://mspi.example.test/invitaciones/aceptar",
        "mspi.invitations.from=mspi@example.test", "mspi.invitations.min-password-length=15",
        "mspi.identity.attempts-per-account=30", "mspi.identity.attempts-per-address=60"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class, InvitationTests.MailFixture.class, PlatformCompanyTests.SecretsFixture.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformCompanyTests {

    private static final String ROOT = "/api/v1/plataforma/empresas";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired TestDatabase database;
    @Autowired PasswordEncoder encoder;
    @Autowired InvitationTests.RecordingMail mail;
    @Autowired RuntimeSecrets secrets;

    private Account administrator;
    private MockHttpSession ap;

    @BeforeAll
    void grantFirstAdministratorByTechnicalScript() throws Exception {
        administrator = account();
        database.provision("platform-admin", "-v", "usuario_id=" + administrator.id());
        database.provision("platform-admin", "-v", "usuario_id=" + administrator.id());
    }

    @BeforeEach
    void startSession() throws Exception {
        mail.messages.clear();
        mail.fail = false;
        ap = login(administrator);
    }

    @Test
    void requiresPlatformRoleAndReservesOnceForTheSameRequest() throws Exception {
        var member = account();
        assertThatThrownBy(() -> database.provision("platform-admin", "-v", "usuario_id=" + member.id()))
                .isInstanceOf(IllegalStateException.class);
        var ordinary = login(member);
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOT).session(ordinary)).andExpect(status().isForbidden());
        reserve(ordinary, UUID.randomUUID(), "Empresa", "America/Bogota", member.email())
                .andExpect(status().isForbidden());
        reserve(ap, UUID.randomUUID(), "Empresa", "Zona/invalida", member.email())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields[0].field").value("zonaHoraria"));
        mvc.perform(post(ROOT).session(ap).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("nombre", "Empresa", "zonaHoraria", "America/Bogota",
                                "contactoInicial", member.email()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields[0].field").value("Idempotency-Key"));

        UUID key = UUID.randomUUID();
        var result = reserve(ap, key, "Empresa", "America/Bogota", member.email())
                .andExpect(status().isCreated()).andExpect(jsonPath("$.estado").value("EN_PREPARACION")).andReturn();
        var created = company(result);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(member.email(), "esquemaInterno");
        assertThat(company(reserve(ap, key, "Empresa", "America/Bogota", member.email())
                .andExpect(status().isCreated()).andReturn()).id()).isEqualTo(created.id());
        reserve(ap, key, "Otra", "America/Bogota", member.email()).andExpect(status().isConflict());
        assertThat(company(reserve(ap, UUID.randomUUID(), "Empresa", "America/Bogota", member.email())
                .andExpect(status().isCreated()).andReturn()).id()).isNotEqualTo(created.id());
        assertThat(count("SELECT count(*) FROM comun.eventos_plataforma WHERE empresa_id = ? AND operacion = 'RESERVAR'",
                created.id())).isEqualTo(1);
        mvc.perform(get(ROOT).session(ap).param("tamano", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.empresas.length()").value(1)).andExpect(jsonPath("$.hayMas").value(true));
        try (var runtime = database.runtime(); var statement = runtime.createStatement()) {
            assertThatThrownBy(() -> statement.executeQuery("SELECT contacto_inicial FROM comun.empresas"))
                    .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE comun.empresas SET estado = 'ACTIVA'"))
                    .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    void preparesWithHu005AndGrantsOnlyFirstAeThroughAcceptance() throws Exception {
        var recipient = account();
        var pending = reserve(recipient);
        revisionCommand(pending, "activar").andExpect(status().isConflict());
        var tenant = tenant(pending.id());
        assertThat(prepare(tenant, false)).isEqualTo(3);
        var waiting = detail(pending.id());
        assertThat(waiting.state()).isEqualTo("EN_PREPARACION");
        revisionCommand(waiting, "activar").andExpect(status().isConflict());

        database.setTenantPasswords(tenant);
        configureRuntime(tenant);
        assertThat(prepare(tenant, true)).isZero();
        var verified = detail(pending.id());
        var active = company(revisionCommand(verified, "activar").andExpect(status().isOk())
                .andExpect(jsonPath("$.resultadoPreparacion").value("VERIFICADO"))
                .andExpect(jsonPath("$.estado").value("ACTIVA")).andReturn());
        assertThat(active.invitation()).isNotNull();
        assertThat(mail.messages).hasSize(1);
        assertThat(mail.messages.get(0).getTo()).containsExactly(recipient.email());
        var invited = login(recipient);
        context(invited, active.id()).andExpect(status().isForbidden());
        accept(invited, mail.latestToken()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.roles.length()").value(1)).andExpect(jsonPath("$.roles[0]").value("AE"));
        context(invited, active.id()).andExpect(status().isOk());
        context(ap, active.id()).andExpect(status().isForbidden());
    }

    @Test
    void deliveryFailurePreservesVerifiedPreparationAndCanBeRetried() throws Exception {
        var recipient = account();
        var verified = prepare(reserve(recipient));
        mail.fail = true;
        revisionCommand(verified, "activar").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ENVIO_NO_DISPONIBLE"));
        var failed = detail(verified.id());
        assertThat(failed.state()).isEqualTo("ERROR_APROVISIONAMIENTO");
        assertThat(failed.failure()).isNotNull();
        assertThat(failed.invitation()).isNull();
        assertThat(count("SELECT count(*) FROM comun.invitaciones WHERE empresa_id = ?", failed.id())).isZero();
        accept(login(recipient), mail.latestToken()).andExpect(status().isConflict());

        mail.fail = false;
        revisionCommand(verified, "activar").andExpect(status().isConflict());
        var active = company(revisionCommand(failed, "activar").andExpect(status().isOk()).andReturn());
        assertThat(active.state()).isEqualTo("ACTIVA");
        assertThat(active.failure()).isNull();
        assertThat(active.id()).isEqualTo(verified.id());
        assertThat(count("SELECT count(*) FROM comun.intentos_preparacion WHERE empresa_id = ?", active.id())).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM comun.eventos_plataforma WHERE empresa_id = ? AND operacion = 'FALLO_ALTA'",
                active.id())).isEqualTo(1);
    }

    @Test
    void suspensionBlocksOpenSessionAndReactivationChecksLiveRuntime() throws Exception {
        var recipient = account();
        var verified = prepare(reserve(recipient));
        var active = company(revisionCommand(verified, "activar").andExpect(status().isOk()).andReturn());
        var member = login(recipient);
        accept(member, mail.latestToken()).andExpect(status().isCreated());
        context(member, active.id()).andExpect(status().isOk());
        stateCommand(active, "suspender", " ").andExpect(status().isBadRequest());
        mvc.perform(post(ROOT + "/{id}/suspender", active.id()).session(ap).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("revisionEsperada", active.revision(), "motivo", "Mantenimiento"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALIDO"));

        var suspended = company(stateCommand(active, "suspender", "Mantenimiento")
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("SUSPENDIDA")).andReturn());
        context(member, active.id()).andExpect(status().isForbidden());
        stateCommand(active, "reactivar", "Revisión antigua").andExpect(status().isConflict());
        stateCommand(suspended, "suspender", "Repetición").andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(suspended.revision()))
                .andExpect(jsonPath("$.motivoEstado").value("Mantenimiento"));
        assertThat(count("SELECT count(*) FROM comun.eventos_plataforma WHERE empresa_id = ? AND operacion = 'SUSPENDER'",
                suspended.id())).isEqualTo(1);

        var scope = MigrationScope.tenant(active.id());
        sql("REVOKE SELECT ON " + scope.schema() + ".contexto_empresa FROM " + scope.runtimeRole());
        try {
            stateCommand(suspended, "reactivar", "Verificación pendiente").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ESTRUCTURA_NO_COMPATIBLE"));
            context(member, active.id()).andExpect(status().isForbidden());
        } finally {
            sql("GRANT SELECT ON " + scope.schema() + ".contexto_empresa TO " + scope.runtimeRole());
        }
        sql("GRANT USAGE ON SCHEMA comun TO " + scope.runtimeRole());
        try {
            stateCommand(suspended, "reactivar", "Permisos excesivos").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ESTRUCTURA_NO_COMPATIBLE"));
        } finally {
            sql("REVOKE USAGE ON SCHEMA comun FROM " + scope.runtimeRole());
        }
        stateCommand(suspended, "reactivar", "Verificado").andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ACTIVA")).andExpect(jsonPath("$.motivoEstado").value("Verificado"));
        context(member, active.id()).andExpect(status().isOk());
    }

    @Test
    void replacesAnExpiredInitialInvitationBeforeFirstAcceptanceOnly() throws Exception {
        var recipient = account();
        var verified = prepare(reserve(recipient));
        var active = company(revisionCommand(verified, "activar").andExpect(status().isOk()).andReturn());
        String expired = mail.latestToken();
        sql("UPDATE comun.invitaciones SET creada_en = CURRENT_TIMESTAMP - interval '2 days',"
                + " vence_en = CURRENT_TIMESTAMP - interval '1 day' WHERE id = ?", active.invitation());
        var replacement = company(revisionCommand(active, "invitacion-inicial/reemitir")
                .andExpect(status().isOk()).andReturn());
        assertThat(replacement.invitation()).isNotEqualTo(active.invitation());
        var invited = login(recipient);
        accept(invited, expired).andExpect(status().isConflict());
        accept(invited, mail.latestToken()).andExpect(status().isCreated());
        revisionCommand(replacement, "invitacion-inicial/reemitir").andExpect(status().isConflict());
        assertThat(mail.messages).hasSize(2);
    }

    private Company reserve(Account recipient) throws Exception {
        return company(reserve(ap, UUID.randomUUID(), "Empresa ficticia", "America/Bogota", recipient.email())
                .andExpect(status().isCreated()).andReturn());
    }

    private ResultActions reserve(MockHttpSession session, UUID key, String name, String zone, String email)
            throws Exception {
        return mvc.perform(post(ROOT).session(session).with(csrf()).header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("nombre", name,
                        "zonaHoraria", zone, "contactoInicial", email))));
    }

    private ResultActions revisionCommand(Company company, String command) throws Exception {
        return mvc.perform(post(ROOT + "/{id}/" + command, company.id()).session(ap).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("revisionEsperada", company.revision()))));
    }

    private ResultActions stateCommand(Company company, String command, String reason) throws Exception {
        return mvc.perform(post(ROOT + "/{id}/" + command, company.id()).session(ap).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("revisionEsperada", company.revision(), "motivo", reason))));
    }

    private Company detail(UUID id) throws Exception {
        return company(mvc.perform(get(ROOT + "/{id}", id).session(ap)).andExpect(status().isOk()).andReturn());
    }

    private ResultActions context(MockHttpSession session, UUID id) throws Exception {
        return mvc.perform(get("/api/v1/empresas/{id}/contexto", id).session(session));
    }

    private ResultActions accept(MockHttpSession session, String token) throws Exception {
        return mvc.perform(post("/api/v1/invitaciones/aceptar").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("token", token))));
    }

    private Company prepare(Company reserved) throws Exception {
        var tenant = tenant(reserved.id());
        assertThat(prepare(tenant, false)).isEqualTo(3);
        database.setTenantPasswords(tenant);
        configureRuntime(tenant);
        assertThat(prepare(tenant, true)).isZero();
        return detail(reserved.id());
    }

    private TestDatabase.Tenant tenant(UUID id) {
        return new TestDatabase.Tenant(MigrationScope.tenant(id), UUID.randomUUID().toString(), UUID.randomUUID().toString());
    }

    private void configureRuntime(TestDatabase.Tenant tenant) {
        secrets.entries.put(tenant.scope().companyId(),
                new TenantConnectionSecret(database.url(), tenant.scope().runtimeRole(), tenant.runtimePassword()));
    }

    private int prepare(TestDatabase.Tenant tenant, boolean withCredentials) {
        var environment = new HashMap<>(database.preparationEnvironment());
        if (withCredentials) {
            environment.put("MSPI_MIGRATION_USER", tenant.scope().migrationRole());
            environment.put("MSPI_MIGRATION_PASSWORD", tenant.migrationPassword());
            environment.put("MSPI_PREPARATION_RUNTIME_PASSWORD", tenant.runtimePassword());
        }
        var output = new ByteArrayOutputStream();
        try (var stream = new PrintStream(output)) {
            return MigrationCli.run(new String[]{"prepare", tenant.scope().companyId().toString()}, environment,
                    stream, stream);
        }
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
        var response = mvc.perform(post("/api/v1/identidad/sesion").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("correo", account.email(), "contrasena", account.password()))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) response.getRequest().getSession(false);
    }

    private void sql(String statement, Object... values) throws SQLException {
        try (var connection = database.admin(); var prepared = connection.prepareStatement(statement)) {
            for (int i = 0; i < values.length; i++) prepared.setObject(i + 1, values[i]);
            prepared.executeUpdate();
        }
    }

    private long count(String statement, Object value) throws SQLException {
        try (var connection = database.admin(); var prepared = connection.prepareStatement(statement)) {
            prepared.setObject(1, value);
            try (var result = prepared.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private String body(Object value) { return mapper.writeValueAsString(value); }

    private Company company(MvcResult result) {
        var data = mapper.readTree(result.getResponse().getContentAsByteArray());
        return new Company(uuid(data.path("empresaId")), data.path("revision").asLong(), data.path("estado").asString(),
                uuid(data.path("invitacionInicialId")), uuid(data.path("referenciaFallo")));
    }

    private static UUID uuid(JsonNode node) {
        return node.isString() ? UUID.fromString(node.asString()) : null;
    }

    record Account(UUID id, String email, String password) {}
    record Company(UUID id, long revision, String state, UUID invitation, UUID failure) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class SecretsFixture {
        @Bean RuntimeSecrets platformRuntimeSecrets() { return new RuntimeSecrets(); }
    }

    static class RuntimeSecrets implements TenantSecretSource {
        final Map<UUID, TenantConnectionSecret> entries = new ConcurrentHashMap<>();

        @Override
        public TenantConnectionSecret load(UUID companyId) {
            var secret = entries.get(companyId);
            if (secret == null) throw new IllegalArgumentException("Credencial de prueba ausente.");
            return secret;
        }
    }
}
