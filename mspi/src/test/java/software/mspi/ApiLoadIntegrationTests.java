package software.mspi;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mspi.load.json-bytes=1024", "mspi.load.max-sessions=2",
        "mspi.load.requests-per-address=8", "mspi.load.login-requests-per-address=6"
})
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class ApiLoadIntegrationTests {
    private static final String CSRF = "/api/v1/identidad/csrf";
    private static final String SESSION = "/api/v1/identidad/sesion";
    private final HttpClient client = HttpClient.newHttpClient();
    @LocalServerPort int port;
    @Autowired TestDatabase database;
    @Autowired PasswordEncoder passwords;
    @Autowired JsonMapper mapper;

    @Test
    void actualServerBoundsSessionsAndChunkedBodiesAndRecoversAfterLogout() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        String password = UUID.randomUUID().toString();
        try (var connection = database.admin(); var statement = connection.prepareStatement("""
                INSERT INTO comun.usuarios(id, nombre_visible, correo, clave_correo, correo_verificado_en,
                    estado_cuenta, hash_credencial)
                VALUES (?, 'Prueba de carga', ?, ?, CURRENT_TIMESTAMP, 'HABILITADA', ?)
                """)) {
            statement.setObject(1, UUID.randomUUID()); statement.setString(2, email);
            statement.setString(3, email); statement.setString(4, passwords.encode(password)); statement.executeUpdate();
        }
        var first = send("GET", CSRF, null, null, null, false);
        assertThat(first.statusCode()).isEqualTo(200);
        String cookie = cookie(first);
        assertThat(send("GET", CSRF, null, null, null, false).statusCode()).isEqualTo(200);
        var rejected = send("GET", CSRF, null, null, null, false);
        assertThat(rejected.statusCode()).isEqualTo(503);
        assertThat(rejected.headers().firstValue("Retry-After")).contains("1");
        assertThat(rejected.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(rejected.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(mapper.readTree(rejected.body()).path("code").asString()).isEqualTo("CAPACIDAD_AGOTADA");
        assertThat(mapper.readTree(rejected.body()).path("correlationId").asString())
                .isEqualTo(rejected.headers().firstValue("X-Correlation-Id").orElseThrow());

        var login = send("POST", SESSION, cookie, token(first),
                mapper.writeValueAsString(Map.of("correo", email, "contrasena", password)), false);
        assertThat(login.statusCode()).isEqualTo(200);
        cookie = cookie(login);
        var csrf = send("GET", CSRF, cookie, null, null, false);
        assertThat(csrf.statusCode()).isEqualTo(200);
        String large = mapper.writeValueAsString(Map.of("correo", email, "contrasena", "x".repeat(4096)));
        for (boolean chunked : new boolean[]{false, true}) {
            var oversized = send("POST", SESSION, cookie, token(csrf), large, chunked);
            assertThat(oversized.statusCode()).isEqualTo(413);
            assertThat(mapper.readTree(oversized.body()).path("code").asString()).isEqualTo("CARGA_DEMASIADO_GRANDE");
            assertThat(oversized.body()).doesNotContain(email, password, "x".repeat(100));
        }
        assertThat(send("GET", SESSION, cookie, null, null, false).statusCode()).isEqualTo(200);
        assertThat(send("DELETE", SESSION, cookie, token(csrf), null, false).statusCode()).isEqualTo(204);
        var replacement = send("GET", CSRF, null, null, null, false);
        assertThat(replacement.statusCode()).isEqualTo(200);
        var secondLogin = send("POST", SESSION, cookie(replacement), token(replacement),
                mapper.writeValueAsString(Map.of("correo", email, "contrasena", password)), false);
        assertThat(secondLogin.statusCode()).isEqualTo(200);
        String active = cookie(secondLogin);
        HttpResponse<String> burst = null;
        for (int i = 0; i < 10; i++) burst = send("GET", "/api/v1", null, null, null, false);
        assertRateLimited(burst);
        assertRateLimited(send("GET", SESSION, "MSPISESSION=not-a-server-session", null, null, false));
        assertThat(send("GET", SESSION, active, null, null, false).statusCode()).isEqualTo(200);
        assertThat(send("GET", CSRF, active, null, null, false).statusCode()).isEqualTo(200);
        // A real cookie must not move POST login to the account budget.
        for (int i = 0; i < 7; i++) burst = send("POST", SESSION, active, null, "{}", false);
        assertRateLimited(burst);
        assertThat(send("GET", SESSION, active, null, null, false).statusCode()).isEqualTo(200);
    }

    private void assertRateLimited(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After")).isPresent();
        assertThat(mapper.readTree(response.body()).path("code").asString()).isEqualTo("DEMASIADAS_SOLICITUDES");
    }

    private HttpResponse<String> send(String method, String path, String cookie, String token,
                                      String body, boolean chunked) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (cookie != null) builder.header("Cookie", cookie);
        if (token != null) builder.header("X-CSRF-TOKEN", token);
        var publisher = HttpRequest.BodyPublishers.noBody();
        if (body != null) {
            builder.header("Content-Type", "application/json");
            publisher = chunked ? HttpRequest.BodyPublishers.ofInputStream(() ->
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                    : HttpRequest.BodyPublishers.ofString(body);
        }
        return client.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }
    private String token(HttpResponse<String> response) { return mapper.readTree(response.body()).path("token").asString(); }
    private String cookie(HttpResponse<String> response) {
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
}
