package software.mspi;

import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
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
        "mspi.invitations.from=mspi@example.test", "mspi.invitations.min-password-length=15"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class, InvitationTests.MailFixture.class})
class InvitationTests {

    private static final String ISSUE = "/api/v1/empresas/{empresaId}/invitaciones";
    private static final String ACCEPT = "/api/v1/invitaciones/aceptar";
    private static final String ENROLL = "/api/v1/invitaciones/alta";
    private static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]{43})");

    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired PasswordEncoder passwords;
    @Autowired TestDatabase database;
    @Autowired RecordingMail mail;

    private UUID company;
    private MockHttpSession ae;

    @BeforeEach
    void prepare() throws Exception {
        mail.messages.clear();
        mail.fail = false;
        company = company();
        var issuer = account();
        member(company, issuer.id(), "AE");
        ae = login(issuer);
    }

    @Test
    void issuesToVerifiedRecipientAndGrantsOnlyOfferedRoles() throws Exception {
        var recipient = account();
        var stranger = account();
        var other = login(stranger);
        var invited = login(recipient);
        var gs = account();
        member(company, gs.id(), "GS");

        issue(login(gs), company, recipient.email(), "AU").andExpect(status().isForbidden());
        issue(ae, company, recipient.email(), "AU", "AU").andExpect(status().isBadRequest());
        issue(ae, company, recipient.email(), "AP").andExpect(status().isBadRequest());
        assertThat(mail.messages).isEmpty();

        var issued = issueToken(ae, company, recipient.email().toUpperCase(Locale.ROOT), "AU", "GS");
        assertThat(issued.response().getResponse().getContentAsString()).doesNotContain(issued.token(), recipient.email());
        assertThat(mail.messages.get(0).getTo()).containsExactly(recipient.email().toUpperCase(Locale.ROOT));
        assertThat(mail.messages.get(0).getText()).contains("https://mspi.example.test/invitaciones/aceptar#token=");
        assertThat(Instant.parse(json(issued.response(), "venceEn"))).isAfter(Instant.now().plus(Duration.ofHours(71)));

        mvc.perform(post(ACCEPT).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("token", issued.token()))))
                .andExpect(status().isUnauthorized());
        accept(other, issued.token()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITACION_NO_DISPONIBLE"));
        mvc.perform(post(ACCEPT).session(invited).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("token", issued.token(), "roles", new String[]{"AE"}, "empresaId", company))))
                .andExpect(status().isBadRequest());
        accept(invited, issued.token()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.usuarioId").value(recipient.id().toString()))
                .andExpect(jsonPath("$.roles[0]").value("AU"))
                .andExpect(jsonPath("$.roles[1]").value("GS"));
        accept(invited, issued.token()).andExpect(status().isConflict());
        assertContext(invited, "AU", "GS");

        try (var connection = database.admin(); var statement = connection.prepareStatement(
                "SELECT huella_token FROM comun.invitaciones WHERE id = ?")) {
            statement.setObject(1, issued.id());
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBytes(1)).isEqualTo(MessageDigest.getInstance("SHA-256")
                        .digest(issued.token().getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            }
        }
        try (var connection = database.runtime(); var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeQuery("SELECT huella_token FROM comun.invitaciones"))
                    .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    void allowsEnrollmentOnlyWithMailboxProofAndNeverReplacesAnExistingAccount() throws Exception {
        var existing = account();
        var existingInvite = issueToken(ae, company, existing.email(), "AU");
        enroll(existingInvite.token(), "Nueva cuenta", UUID.randomUUID().toString())
                .andExpect(status().isConflict());
        login(existing);

        String email = UUID.randomUUID() + "@example.test";
        var issued = issueToken(ae, company, email, "RA");
        String password = UUID.randomUUID().toString();
        mvc.perform(post(ENROLL).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("token", issued.token(), "nombreVisible", "Persona", "contrasena", password))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALIDO"));
        enroll("x".repeat(43), "Persona", password).andExpect(status().isConflict());
        enroll(issued.token(), "Persona", "muy-corta").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("contrasena"));
        var accepted = enroll(issued.token(), "Persona invitada", password).andExpect(status().isCreated()).andReturn();
        UUID user = UUID.fromString(json(accepted, "usuarioId"));
        assertThat(accepted.getResponse().getContentAsString()).doesNotContain(issued.token(), password);
        assertContext(login(new Account(user, email, password)), "RA");
        enroll(issued.token(), "Otra persona", UUID.randomUUID().toString()).andExpect(status().isConflict());
    }

    @Test
    void rejectsRevokedExpiredAndSuspendedInvitationsInCompanyScope() throws Exception {
        var recipient = account();
        var invited = login(recipient);
        var revoked = issueToken(ae, company, recipient.email(), "AU");
        UUID foreignCompany = company();
        var otherAe = account();
        member(foreignCompany, otherAe.id(), "AE");
        revoke(login(otherAe), foreignCompany, revoked.id()).andExpect(status().isConflict());
        revoke(ae, company, revoked.id()).andExpect(status().isNoContent());
        accept(invited, revoked.token()).andExpect(status().isConflict());

        var expired = issueToken(ae, company, recipient.email(), "AU");
        sql("UPDATE comun.invitaciones SET creada_en = CURRENT_TIMESTAMP - interval '2 days',"
                + " vence_en = CURRENT_TIMESTAMP - interval '1 day' WHERE id = ?", expired.id());
        accept(invited, expired.token()).andExpect(status().isConflict());
        revoke(ae, company, expired.id()).andExpect(status().isConflict());

        var suspended = issueToken(ae, company, recipient.email(), "GS");
        sql("UPDATE comun.empresas SET estado = 'SUSPENDIDA' WHERE id = ?", company);
        accept(invited, suspended.token()).andExpect(status().isConflict());
        issue(ae, company, recipient.email(), "GS").andExpect(status().isForbidden());
        sql("UPDATE comun.empresas SET estado = 'ACTIVA' WHERE id = ?", company);
        accept(invited, suspended.token()).andExpect(status().isCreated());
    }

    @Test
    void concurrentConsumptionCreatesOneMembershipAndOldLinksNeverEditIt() throws Exception {
        var recipient = account();
        var offered = issueToken(ae, company, recipient.email(), "AU", "GS");
        var old = issueToken(ae, company, recipient.email(), "AE");
        var first = login(recipient);
        var second = login(recipient);
        var start = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return accept(first, offered.token()).andReturn().getResponse().getStatus();
            });
            var b = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return accept(second, offered.token()).andReturn().getResponse().getStatus();
            });
            assertThat(new int[]{a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)})
                    .containsExactlyInAnyOrder(201, 409);
        } finally {
            executor.shutdownNow();
        }
        accept(first, old.token()).andExpect(status().isConflict());
        assertContext(first, "AU", "GS");
        assertThat(count("SELECT count(*) FROM comun.membresias WHERE empresa_id = ? AND usuario_id = ?",
                company, recipient.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM comun.invitaciones WHERE empresa_id = ? AND aceptada_en IS NOT NULL",
                company)).isEqualTo(1);

        sql("UPDATE comun.membresias SET estado = 'REVOCADA', revocada_en = CURRENT_TIMESTAMP"
                + " WHERE empresa_id = ? AND usuario_id = ?", company, recipient.id());
        accept(second, old.token()).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/empresas/{id}/contexto", company).session(second)).andExpect(status().isForbidden());
    }

    @Test
    void smtpFailureRollsBackTheInvitationEvenIfTheLinkWasDisclosed() throws Exception {
        var recipient = account();
        var invited = login(recipient);
        mail.fail = true;
        var rejected = issue(ae, company, recipient.email(), "AU").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ENVIO_NO_DISPONIBLE")).andReturn();
        String token = mail.latestToken();
        assertThat(rejected.getResponse().getContentAsString()).doesNotContain(token, recipient.email(), "private");
        assertThat(count("SELECT count(*) FROM comun.invitaciones WHERE empresa_id = ?", company)).isZero();
        accept(invited, token).andExpect(status().isConflict());
        mail.fail = false;
        var replacement = issueToken(ae, company, recipient.email(), "AU");
        accept(invited, replacement.token()).andExpect(status().isCreated());
    }

    @Test
    void listsInvitationStatesWithoutExposingTokensOrCrossingCompanies() throws Exception {
        var recipient = account();
        var accepted = issueToken(ae, company, recipient.email(), "AU");
        accept(login(recipient), accepted.token()).andExpect(status().isCreated());
        var revoked = issueToken(ae, company, account().email(), "GS");
        revoke(ae, company, revoked.id()).andExpect(status().isNoContent());
        var expired = issueToken(ae, company, account().email(), "RA");
        sql("""
                UPDATE comun.invitaciones SET creada_en = CURRENT_TIMESTAMP - interval '2 days',
                    vence_en = CURRENT_TIMESTAMP - interval '1 day' WHERE id = ?
                """, expired.id());
        var pending = issueToken(ae, company, account().email(), "AE");
        mvc.perform(get(ISSUE, company).session(login(recipient))).andExpect(status().isForbidden());
        mvc.perform(get(ISSUE, UUID.randomUUID()).session(ae)).andExpect(status().isForbidden());
        var result = mvc.perform(get(ISSUE, company).session(ae)).andExpect(status().isOk()).andReturn();
        var body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(accepted.token(), revoked.token(), expired.token(), pending.token(), "huella");
        var rows = mapper.readTree(body).path("elementos");
        assertThat(rows.valueStream().map(row -> row.path("invitacionId").asString() + ":" + row.path("estado").asString()))
                .containsExactlyInAnyOrder(accepted.id() + ":ACEPTADA", revoked.id() + ":REVOCADA",
                        expired.id() + ":VENCIDA", pending.id() + ":PENDIENTE");
    }

    private ResultActions issue(MockHttpSession session, UUID companyId, String email, String... roles) throws Exception {
        return mvc.perform(post(ISSUE, companyId).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("correo", email, "roles", roles))));
    }

    private Issued issueToken(MockHttpSession session, UUID companyId, String email, String... roles) throws Exception {
        var response = issue(session, companyId, email, roles).andExpect(status().isCreated()).andReturn();
        return new Issued(UUID.fromString(json(response, "invitacionId")), mail.latestToken(), response);
    }

    private ResultActions accept(MockHttpSession session, String token) throws Exception {
        return mvc.perform(post(ACCEPT).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("token", token))));
    }

    private ResultActions enroll(String token, String name, String password) throws Exception {
        return mvc.perform(post(ENROLL).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("token", token, "nombreVisible", name, "contrasena", password))));
    }

    private ResultActions revoke(MockHttpSession session, UUID companyId, UUID invitation) throws Exception {
        return mvc.perform(post(ISSUE + "/{invitacionId}/revocar", companyId, invitation).session(session).with(csrf()));
    }

    private void assertContext(MockHttpSession session, String... roles) throws Exception {
        var response = mvc.perform(get("/api/v1/empresas/{id}/contexto", company).session(session))
                .andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(response.getResponse().getContentAsByteArray()).path("roles").valueStream()
                .map(node -> node.asString())).containsExactly(roles);
    }

    private UUID company() throws Exception {
        var tenant = database.reserveAndProvisionTenant();
        database.tenantFlyway(tenant).migrate();
        UUID id = tenant.scope().companyId();
        sql("UPDATE comun.empresas SET estado = 'ACTIVA', version_estructura = '3' WHERE id = ?", id);
        return id;
    }

    private Account account() throws SQLException {
        UUID id = UUID.randomUUID();
        String email = id + "@example.test";
        String password = UUID.randomUUID().toString();
        sql("""
                INSERT INTO comun.usuarios(id, nombre_visible, correo, clave_correo, correo_verificado_en,
                    estado_cuenta, hash_credencial)
                VALUES (?, 'Persona ficticia', ?, ?, CURRENT_TIMESTAMP, 'HABILITADA', ?)
                """, id, email, email, passwords.encode(password));
        return new Account(id, email, password);
    }

    private void member(UUID companyId, UUID actor, String role) throws SQLException {
        UUID membership = UUID.randomUUID();
        try (var connection = database.admin();
                var insert = connection.prepareStatement("""
                        INSERT INTO comun.membresias(id, empresa_id, usuario_id, estado, rol_ancla)
                        VALUES (?, ?, ?, 'ACTIVA', ?)
                        """);
                var grant = connection.prepareStatement("INSERT INTO comun.membresia_roles VALUES (?, ?)")) {
            connection.setAutoCommit(false);
            insert.setObject(1, membership);
            insert.setObject(2, companyId);
            insert.setObject(3, actor);
            insert.setString(4, role);
            insert.executeUpdate();
            grant.setObject(1, membership);
            grant.setString(2, role);
            grant.executeUpdate();
            connection.commit();
        }
    }

    private MockHttpSession login(Account account) throws Exception {
        var response = mvc.perform(post("/api/v1/identidad/sesion").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("correo", account.email(), "contrasena", account.password()))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) response.getRequest().getSession(false);
    }

    private void sql(String statement, Object... values) throws SQLException {
        try (var connection = database.admin(); var prepared = connection.prepareStatement(statement)) {
            for (int i = 0; i < values.length; i++) {
                prepared.setObject(i + 1, values[i]);
            }
            prepared.executeUpdate();
        }
    }

    private long count(String statement, Object... values) throws SQLException {
        try (var connection = database.admin(); var prepared = connection.prepareStatement(statement)) {
            for (int i = 0; i < values.length; i++) {
                prepared.setObject(i + 1, values[i]);
            }
            try (var result = prepared.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private String body(Object value) {
        return mapper.writeValueAsString(value);
    }

    private String json(MvcResult response, String field) {
        return mapper.readTree(response.getResponse().getContentAsByteArray()).path(field).asString();
    }

    record Account(UUID id, String email, String password) {}
    record Issued(UUID id, String token, MvcResult response) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class MailFixture {
        @Bean RecordingMail recordingMail() { return new RecordingMail(); }
    }

    static class RecordingMail implements MailSender {
        final CopyOnWriteArrayList<SimpleMailMessage> messages = new CopyOnWriteArrayList<>();
        volatile boolean fail;

        @Override public void send(SimpleMailMessage message) { send(new SimpleMailMessage[]{message}); }

        @Override public void send(SimpleMailMessage... batch) {
            for (SimpleMailMessage message : batch) {
                messages.add(new SimpleMailMessage(message));
            }
            if (fail) {
                throw new MailSendException("private SMTP diagnostic");
            }
        }

        String latestToken() {
            var match = TOKEN.matcher(messages.get(messages.size() - 1).getText());
            assertThat(match.find()).isTrue();
            return match.group(1);
        }
    }
}
