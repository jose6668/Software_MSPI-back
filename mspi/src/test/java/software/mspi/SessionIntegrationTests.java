package software.mspi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mspi.identity.session-lifetime=10m",
        "mspi.identity.attempts-per-account=3",
        "mspi.identity.attempts-per-address=5"
})
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class, SessionIntegrationTests.TestClockConfiguration.class})
class SessionIntegrationTests {

    private static final String SESSION = "/api/v1/identidad/sesion";
    private static final String CSRF = "/api/v1/identidad/csrf";

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort int port;
    @Autowired TestDatabase database;
    @Autowired PasswordEncoder encoder;
    @Autowired JsonMapper mapper;
    @Autowired TestClock clock;

    private String email;
    private String password;
    private String hash;
    private UUID user;

    @BeforeEach
    void seedAccount() throws Exception {
        clock.advance(Duration.ofMinutes(2));
        email = UUID.randomUUID() + "@example.test";
        password = UUID.randomUUID().toString();
        hash = encoder.encode(password);
        user = insert(email, "HABILITADA");
    }

    @Test
    void createsProtectedCookieRotatesSessionAndCsrfAndInvalidatesOnLogout() throws Exception {
        assertProblem(send("GET", SESSION, null, null, null), 401, "NO_AUTENTICADO");
        var before = csrf(null);
        assertThat(before.setCookie()).contains("HttpOnly", "Secure", "SameSite=Lax");
        assertProblem(send("POST", SESSION, before.cookie(), null, credentials(email, password)),
                403, "CSRF_INVALIDO");

        var login = send("POST", SESSION, before.cookie(), before, credentials(email.toUpperCase(Locale.ROOT), password));
        assertThat(login.statusCode()).isEqualTo(200);
        String sessionCookie = cookie(login);
        assertThat(setCookie(login)).contains("HttpOnly", "Secure", "SameSite=Lax");
        assertThat(sessionCookie).isNotEqualTo(before.cookie());
        assertThat(json(login).path("usuarioId").asString()).isEqualTo(user.toString());
        assertThat(json(login).propertyNames()).containsExactlyInAnyOrder("usuarioId", "nombreVisible", "expiraEn");
        assertThat(Instant.parse(json(login).path("expiraEn").asString())).isEqualTo(clock.instant().plusSeconds(600));
        assertThat(login.body()).doesNotContain(password, hash, sessionCookie);
        assertThat(send("GET", SESSION, sessionCookie, null, null).statusCode()).isEqualTo(200);
        assertProblem(send("GET", SESSION, before.cookie(), null, null), 401, "NO_AUTENTICADO");
        var companies = send("GET", "/api/v1/mis-empresas", sessionCookie, null, null);
        assertThat(companies.statusCode()).isEqualTo(200);
        assertThat(json(companies).path("empresas").isEmpty()).isTrue();
        assertProblem(send("GET", "/api/v1/empresas/" + UUID.randomUUID() + "/contexto", sessionCookie, null, null),
                403, "ACCESO_DENEGADO");

        assertProblem(send("DELETE", SESSION, sessionCookie, before, null), 403, "CSRF_INVALIDO");
        assertThat(send("GET", SESSION, sessionCookie, null, null).statusCode()).isEqualTo(200);
        var after = csrf(sessionCookie);
        var logout = send("DELETE", SESSION, sessionCookie, after, null);
        assertThat(logout.statusCode()).isEqualTo(204);
        assertThat(logout.body()).isEmpty();
        assertThat(setCookie(logout)).startsWith("MSPISESSION=;").contains("Max-Age=0", "Secure", "HttpOnly");
        assertProblem(send("GET", SESSION, sessionCookie, null, null), 401, "NO_AUTENTICADO");
    }

    @Test
    void expiredSessionIsRejectedAtTheBoundaryWithoutCleanupJob() throws Exception {
        String sessionCookie = login();
        clock.advance(Duration.ofMinutes(10).minusSeconds(1));
        assertThat(send("GET", SESSION, sessionCookie, null, null).statusCode()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(1));
        assertProblem(send("GET", SESSION, sessionCookie, null, null), 401, "NO_AUTENTICADO");
        assertProblem(send("GET", SESSION, sessionCookie, null, null), 401, "NO_AUTENTICADO");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DESHABILITADA", "PENDIENTE_VERIFICACION"})
    void deactivationRevokesEverySessionAndPreservesOtherAccounts(String state) throws Exception {
        String first = login();
        String second = login();
        String otherEmail = UUID.randomUUID() + "@example.test";
        UUID other = insert(otherEmail, "HABILITADA");
        String otherCookie = login(otherEmail, password);
        assertThat(first).isNotEqualTo(second);
        changeState(state);
        assertRejectedSession(first);
        assertProblem(send("GET", "/api/v1/mis-empresas", second, null, null), 401, "NO_AUTENTICADO");
        assertRejectedSession(second);
        var otherResponse = send("GET", SESSION, otherCookie, null, null);
        assertThat(otherResponse.statusCode()).isEqualTo(200);
        assertThat(json(otherResponse).path("usuarioId").asString()).isEqualTo(other.toString());
        changeState("HABILITADA");
        assertRejectedSession(first);
        assertRejectedSession(second);
        assertThat(send("GET", SESSION, login(), null, null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/mis-empresas", otherCookie, null, null).statusCode()).isEqualTo(200);
    }

    @Test
    void reactivationWithoutInterveningRequestsDoesNotReviveOldCookie() throws Exception {
        String oldCookie = login();
        changeState("DESHABILITADA");
        changeState("HABILITADA");
        assertRejectedSession(oldCookie);
        assertThat(send("GET", SESSION, login(), null, null).statusCode()).isEqualTo(200);
    }

    @Test
    void deletedAccountCannotKeepUsingItsSession() throws Exception {
        String sessionCookie = login();
        String otherEmail = UUID.randomUUID() + "@example.test";
        UUID other = insert(otherEmail, "HABILITADA");
        String otherCookie = login(otherEmail, password);
        try (var connection = database.admin();
                var statement = connection.prepareStatement("DELETE FROM comun.usuarios WHERE id = ?")) {
            statement.setObject(1, user);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        assertRejectedSession(sessionCookie);
        var response = send("GET", SESSION, otherCookie, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("usuarioId").asString()).isEqualTo(other.toString());
    }

    private void assertRejectedSession(String cookie) throws Exception {
        var response = send("GET", SESSION, cookie, null, null);
        assertProblem(response, 401, "NO_AUTENTICADO");
        assertThat(response.body()).doesNotContain(password, hash, email, user.toString());
        assertProblem(send("GET", "/api/v1/mis-empresas", cookie, null, null), 401, "NO_AUTENTICADO");
    }

    private void changeState(String state) throws SQLException {
        try (var connection = database.admin(); var statement = connection.prepareStatement(
                "UPDATE comun.usuarios SET estado_cuenta = ? WHERE id = ?")) {
            statement.setString(1, state);
            statement.setObject(2, user);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    @Test
    void wrongUnknownAndDisabledCredentialsHaveTheSamePublicRejection() throws Exception {
        String disabled = UUID.randomUUID() + "@example.test";
        insert(disabled, "DESHABILITADA");
        var token = csrf(null);
        String detail = null;
        for (var credentials : new Credentials[] {
                new Credentials(email, "wrong-password"),
                new Credentials(UUID.randomUUID() + "@example.test", password),
                new Credentials(disabled, password) }) {
            var result = send("POST", SESSION, token.cookie(), token, credentials);
            assertProblem(result, 401, "CREDENCIALES_INVALIDAS");
            assertThat(result.body()).doesNotContain(credentials.correo(), credentials.contrasena(), hash);
            if (detail == null) {
                detail = json(result).path("detail").asString();
            } else {
                assertThat(json(result).path("detail").asString()).isEqualTo(detail);
            }
        }
    }

    @Test
    void rateLimitsNormalizedAccountsAndAllowsNewAttemptAfterWindow() throws Exception {
        var token = csrf(null);
        for (int i = 0; i < 3; i++) {
            assertProblem(send("POST", SESSION, token.cookie(), token, credentials(email, "wrong-password")),
                    401, "CREDENCIALES_INVALIDAS");
        }
        assertProblem(send("POST", SESSION, token.cookie(), token, credentials(email.toUpperCase(Locale.ROOT), password)),
                429, "DEMASIADOS_INTENTOS");
        clock.advance(Duration.ofMinutes(1));
        assertThat(send("POST", SESSION, token.cookie(), token, credentials(email, password)).statusCode())
                .isEqualTo(200);
    }

    @Test
    void addressLimitCoversDifferentAccountKeysAndIgnoresForwardedHeader() throws Exception {
        var token = csrf(null);
        for (int i = 0; i < 5; i++) {
            var payload = credentials(UUID.randomUUID() + "@example.test", password);
            assertProblem(send("POST", SESSION, token.cookie(), token, payload), 401, "CREDENCIALES_INVALIDAS");
        }
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + SESSION))
                .header("Cookie", token.cookie()).header(token.headerName(), token.token())
                .header("X-Forwarded-For", "192.0.2.123").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(credentials(email, password))))
                .build();
        assertProblem(client.send(request, HttpResponse.BodyHandlers.ofString()), 429, "DEMASIADOS_INTENTOS");
    }

    @Test
    void runtimeCannotChangeCredentialsAndRegistrationRouteIsClosed() throws Exception {
        try (var connection = database.runtime(); var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE comun.usuarios SET hash_credencial = NULL"))
                    .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
        }
        try (var connection = database.admin(); var statement = connection.prepareStatement(
                "UPDATE comun.usuarios SET hash_credencial = 'invalid-format' WHERE id = ?")) {
            statement.setObject(1, user);
            assertThatThrownBy(statement::executeUpdate)
                    .isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23514"));
        }
        var token = csrf(null);
        assertProblem(send("POST", "/api/v1/identidad/registro", token.cookie(), token, credentials(email, password)),
                401, "NO_AUTENTICADO");
        assertThat(send("GET", "/login", null, null, null).statusCode()).isEqualTo(403);
    }

    private UUID insert(String address, String state) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var connection = database.admin(); var statement = connection.prepareStatement("""
                INSERT INTO comun.usuarios(id, nombre_visible, correo, clave_correo, correo_verificado_en,
                                          estado_cuenta, hash_credencial)
                VALUES (?, 'Persona de prueba', ?, ?, CURRENT_TIMESTAMP, ?, ?)
                """)) {
            statement.setObject(1, id);
            statement.setString(2, address);
            statement.setString(3, address.toLowerCase(Locale.ROOT));
            statement.setString(4, state);
            statement.setString(5, hash);
            statement.executeUpdate();
        }
        return id;
    }

    private String login() throws Exception {
        return login(email, password);
    }

    private String login(String address, String secret) throws Exception {
        var token = csrf(null);
        var response = send("POST", SESSION, token.cookie(), token, credentials(address, secret));
        assertThat(response.statusCode()).isEqualTo(200);
        return cookie(response);
    }

    private Csrf csrf(String existingCookie) throws Exception {
        var result = send("GET", CSRF, existingCookie, null, null);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        String cookie = existingCookie == null ? cookie(result) : existingCookie;
        return new Csrf(cookie, result.headers().firstValue("Set-Cookie").orElse(""),
                json(result).path("headerName").asString(), json(result).path("token").asString());
    }

    private HttpResponse<String> send(String method, String path, String cookie, Csrf csrf, Object body)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        if (csrf != null) {
            request.header(csrf.headerName(), csrf.token());
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
        assertThat(json(response).path("code").asString()).isEqualTo(code);
        assertThat(json(response).path("correlationId").asString())
                .isEqualTo(response.headers().firstValue("X-Correlation-Id").orElseThrow());
    }

    private JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    private static String cookie(HttpResponse<String> response) {
        return setCookie(response).split(";", 2)[0];
    }

    private static String setCookie(HttpResponse<String> response) {
        return response.headers().firstValue("Set-Cookie").orElseThrow();
    }

    private static Credentials credentials(String address, String password) {
        return new Credentials(address, password);
    }

    record Credentials(String correo, String contrasena) {}
    record Csrf(String cookie, String setCookie, String headerName, String token) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {
        @Bean TestClock clock() { return new TestClock(); }
    }

    static class TestClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-06T12:00:00Z");

        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
