package software.mspi;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"mspi.recovery.enabled=true", "mspi.recovery.validity=20m",
        "mspi.recovery.reset-url=https://mspi.example.test/recuperar",
        "mspi.recovery.from=mspi@example.test", "mspi.recovery.min-password-length=15",
        "mspi.recovery.attempt-window=2m", "mspi.recovery.attempts-per-key=4",
        "mspi.recovery.attempts-per-address=40", "mspi.recovery.total-attempts=100",
        "mspi.recovery.concurrent-checks=4", "mspi.recovery.queue-capacity=10",
        "mspi.identity.attempts-per-address=60"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class, InvitationTests.MailFixture.class})
class AccountRecoveryTests {

    private static final String REQUEST = "/api/v1/identidad/recuperacion";
    private static final String CONFIRM = REQUEST + "/confirmar";
    private static final String SESSION = "/api/v1/identidad/sesion";
    private static final String NEW_PASSWORD = "contraseña nueva de prueba";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired TestDatabase database;
    @Autowired PasswordEncoder passwords;
    @Autowired InvitationTests.RecordingMail mail;
    @Autowired @Qualifier("recoveryDeliveryExecutor") ThreadPoolTaskExecutor delivery;

    @BeforeEach
    void clearMail() {
        mail.messages.clear();
        mail.fail = false;
    }

    @Test
    void blockedPasswordsDoNotConsumeRecoveryProofOrChangeCredentials() throws Exception {
        var user = account("HABILITADA");
        String token = issue(user);
        for (String rejected : new String[]{"12345678901234567890", "PASSWORDPASSWORD"}) {
            var response = confirm(token, rejected).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDACION"))
                    .andExpect(jsonPath("$.fields[0].field").value("contrasena")).andReturn();
            assertThat(response.getResponse().getContentAsString()).doesNotContain(rejected, token);
        }
        assertThat(count("SELECT version_credencial FROM comun.usuarios WHERE id = ?", user.id())).isZero();
        login(user.email(), user.password()).andExpect(status().isOk());
        confirm(token, NEW_PASSWORD).andExpect(status().isNoContent());
        login(user.email(), NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void publicRequestDoesNotDiscloseAccountAndUsesVerifiedRecipient() throws Exception {
        var user = account("HABILITADA");
        var disabled = account("DESHABILITADA");
        mvc.perform(post(REQUEST).contentType(MediaType.APPLICATION_JSON).content(body(Map.of("correo", user.email()))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALIDO"));
        request("correo-invalido").andExpect(status().isBadRequest());
        for (String email : new String[]{user.email().toUpperCase(Locale.ROOT), disabled.email(), unknownEmail()}) {
            var result = request(email).andExpect(status().isAccepted()).andReturn();
            assertThat(result.getResponse().getContentAsString()).isEmpty();
            assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        }
        drain();
        assertThat(mail.messages).hasSize(1);
        assertThat(mail.messages.get(0).getTo()).containsExactly(user.email());
        assertThat(mail.messages.get(0).getText()).contains("https://mspi.example.test/recuperar#token=");
        assertThat(count("SELECT count(*) FROM comun.comprobantes_recuperacion WHERE usuario_id = ?", user.id())).isOne();
        try (var runtime = database.runtime(); var statement = runtime.createStatement()) {
            assertThatThrownBy(() -> statement.executeQuery("SELECT huella FROM comun.comprobantes_recuperacion"))
                    .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE comun.usuarios SET version_credencial = 0"))
                    .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    void resetsOnceAndInvalidatesPreviousSessionsAndOtherLinksWithoutChangingAccess() throws Exception {
        var user = account("HABILITADA");
        var other = account("HABILITADA");
        UUID suspended = company("SUSPENDIDA");
        UUID active = company("ACTIVA");
        member(user, suspended, false);
        member(user, active, true);
        var first = login(user);
        var second = login(user);
        var unaffected = login(other);

        String superseded = issue(user);
        String token = issue(user);
        confirm(token, NEW_PASSWORD).andExpect(status().isNoContent());
        for (String invalid : new String[]{token, superseded}) {
            confirm(invalid, NEW_PASSWORD).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("RECUPERACION_NO_DISPONIBLE"));
        }
        for (MockHttpSession stale : new MockHttpSession[]{first, second}) {
            mvc.perform(get(SESSION).session(stale)).andExpect(status().isUnauthorized());
            assertThat(stale.isInvalid()).isTrue();
        }
        mvc.perform(get(SESSION).session(unaffected)).andExpect(status().isOk());
        login(user.email(), user.password()).andExpect(status().isUnauthorized());
        var renewed = session(login(user.email(), NEW_PASSWORD).andExpect(status().isOk()));
        for (UUID company : new UUID[]{suspended, active}) {
            mvc.perform(get("/api/v1/empresas/{id}/contexto", company).session(renewed)).andExpect(status().isForbidden());
        }
        assertThat(count("SELECT version_credencial FROM comun.usuarios WHERE id = ?", user.id())).isOne();
        assertThat(count("""
                SELECT count(*) FROM comun.comprobantes_recuperacion
                WHERE usuario_id = ? AND consumido_en IS NOT NULL
                """, user.id())).isOne();
        assertThat(count("""
                SELECT count(*) FROM comun.comprobantes_recuperacion
                WHERE usuario_id = ? AND invalidado_en IS NOT NULL
                """, user.id())).isOne();
    }

    @Test
    void rejectsExpiredWrongOrNoLongerVerifiedTokensAndRequiresValidNewPassword() throws Exception {
        var user = account("HABILITADA");
        String expired = issue(user);
        sql("""
                UPDATE comun.comprobantes_recuperacion SET emitido_en = CURRENT_TIMESTAMP - interval '2 hours',
                    vence_en = CURRENT_TIMESTAMP - interval '1 hour' WHERE usuario_id = ?
                """, user.id());
        confirm(expired, NEW_PASSWORD).andExpect(status().isConflict());
        confirm("a".repeat(43), NEW_PASSWORD).andExpect(status().isConflict());
        String staleChannel = issue(user);
        sql("UPDATE comun.usuarios SET correo_verificado_en = correo_verificado_en + interval '1 second' WHERE id = ?",
                user.id());
        confirm(staleChannel, NEW_PASSWORD).andExpect(status().isConflict());

        String valid = issue(user);
        confirm(valid, "corta").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields[0].field").value("contrasena"));
        mvc.perform(post(CONFIRM).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("token", valid, "contrasena", NEW_PASSWORD))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALIDO"));
        assertThat(count("SELECT version_credencial FROM comun.usuarios WHERE id = ?", user.id())).isZero();
        confirm(valid, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void simultaneousConfirmationsOfTheSameLinkChangeTheCredentialOnlyOnce() throws Exception {
        var user = account("HABILITADA");
        String token = issue(user);
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return confirm(token, NEW_PASSWORD).andReturn().getResponse().getStatus();
            });
            var second = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return confirm(token, NEW_PASSWORD + " alternativa").andReturn().getResponse().getStatus();
            });
            assertThat(new Integer[]{first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)})
                    .containsExactlyInAnyOrder(204, 409);
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("SELECT version_credencial FROM comun.usuarios WHERE id = ?", user.id())).isOne();
    }

    @Test
    void failedDeliveryRollsBackAndRateLimitsApplyToKnownAndUnknownKeys() throws Exception {
        var user = account("HABILITADA");
        mail.fail = true;
        String failed = issue(user);
        assertThat(count("SELECT count(*) FROM comun.comprobantes_recuperacion WHERE usuario_id = ?", user.id())).isZero();
        confirm(failed, NEW_PASSWORD).andExpect(status().isConflict());
        mail.fail = false;
        String replacement = issue(user);
        confirm(replacement, NEW_PASSWORD).andExpect(status().isNoContent());

        String unknown = unknownEmail();
        for (int index = 0; index < 4; index++) {
            request(unknown).andExpect(status().isAccepted());
        }
        request(unknown).andExpect(status().isTooManyRequests());
        request(user.email()).andExpect(status().isAccepted());
        request(user.email()).andExpect(status().isAccepted());
        request(user.email()).andExpect(status().isTooManyRequests());
        String absentToken = "b".repeat(43);
        for (int index = 0; index < 4; index++) {
            confirm(absentToken, NEW_PASSWORD).andExpect(status().isConflict());
        }
        confirm(absentToken, NEW_PASSWORD).andExpect(status().isTooManyRequests());
        drain();
    }

    private ResultActions request(String email) throws Exception {
        return mvc.perform(post(REQUEST).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("correo", email))));
    }

    private String issue(Account user) throws Exception {
        request(user.email()).andExpect(status().isAccepted());
        drain();
        return mail.latestToken();
    }

    private void drain() throws Exception {
        delivery.submit(() -> {}).get(10, TimeUnit.SECONDS);
    }

    private ResultActions confirm(String token, String password) throws Exception {
        return mvc.perform(post(CONFIRM).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("token", token, "contrasena", password))));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post(SESSION).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("correo", email, "contrasena", password))));
    }

    private MockHttpSession login(Account user) throws Exception {
        return session(login(user.email(), user.password()).andExpect(status().isOk()));
    }

    private static MockHttpSession session(ResultActions response) {
        return (MockHttpSession) response.andReturn().getRequest().getSession(false);
    }

    private Account account(String state) throws SQLException {
        var user = new Account(UUID.randomUUID(), unknownEmail(), UUID.randomUUID().toString());
        sql("""
                INSERT INTO comun.usuarios(id, nombre_visible, correo, clave_correo, correo_verificado_en,
                    estado_cuenta, hash_credencial)
                VALUES (?, 'Persona de prueba', ?, ?, CURRENT_TIMESTAMP, ?, ?)
                """, user.id(), user.email(), user.email(), state, passwords.encode(user.password()));
        return user;
    }

    private UUID company(String state) throws SQLException {
        UUID company = database.reserveTenant().scope().companyId();
        sql("UPDATE comun.empresas SET estado = ?, version_estructura = '8' WHERE id = ?", state, company);
        return company;
    }

    private void member(Account user, UUID company, boolean revoked) throws SQLException {
        try (var connection = database.admin();
                var insert = connection.prepareStatement("""
                        INSERT INTO comun.membresias(id, empresa_id, usuario_id, estado, rol_ancla, revocada_en)
                        VALUES (?, ?, ?, ?, 'AE', CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END)
                        """);
                var grant = connection.prepareStatement("INSERT INTO comun.membresia_roles VALUES (?, 'AE')")) {
            connection.setAutoCommit(false);
            UUID membership = UUID.randomUUID();
            insert.setObject(1, membership);
            insert.setObject(2, company);
            insert.setObject(3, user.id());
            insert.setString(4, revoked ? "REVOCADA" : "ACTIVA");
            insert.setBoolean(5, revoked);
            insert.executeUpdate();
            grant.setObject(1, membership);
            grant.executeUpdate();
            connection.commit();
        }
    }

    private void sql(String statement, Object... values) throws SQLException {
        try (var connection = database.admin(); var query = connection.prepareStatement(statement)) {
            for (int index = 0; index < values.length; index++) {
                query.setObject(index + 1, values[index]);
            }
            query.executeUpdate();
        }
    }

    private long count(String statement, Object... values) throws SQLException {
        try (var connection = database.admin(); var query = connection.prepareStatement(statement)) {
            for (int index = 0; index < values.length; index++) {
                query.setObject(index + 1, values[index]);
            }
            try (var rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private static String unknownEmail() {
        return UUID.randomUUID() + "@example.test";
    }

    private String body(Object value) {
        return mapper.writeValueAsString(value);
    }

    record Account(UUID id, String email, String password) {}
}
