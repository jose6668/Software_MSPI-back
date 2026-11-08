package software.mspi;

import java.io.IOException;
import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.core.env.Environment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

abstract class AbstractHttpsIntegrationTests {
    @LocalServerPort int port;
    @Autowired ServletWebServerApplicationContext context;
    @Autowired Environment environment;
    @DynamicPropertySource
    static void tls(DynamicPropertyRegistry properties) {
        var material = HttpsTestMaterial.INSTANCE;
        properties.add("MSPI_TLS_KEYSTORE", material::keyStoreUri);
        properties.add("MSPI_TLS_KEYSTORE_PASSWORD", () -> material.password);
        properties.add("MSPI_HTTPS_LAN_ADDRESS", () -> material.lanAddress);
    }
    private String address() { return environment.getProperty("server.address"); }
    private URI uri(String scheme, String path) { return URI.create(scheme + "://" + address() + ":" + port + path); }
    private HttpClient client(String protocol) throws Exception {
        var parameters = new SSLParameters(); parameters.setProtocols(new String[]{protocol});
        return HttpClient.newBuilder().sslContext(HttpsTestMaterial.INSTANCE.clientContext())
                .sslParameters(parameters).connectTimeout(Duration.ofSeconds(5)).build();
    }
    @Test
    void usesOneTlsConnectorAndBothModernTlsVersionsWithoutHttpDowngrade() throws Exception {
        var connectors = ((TomcatWebServer) context.getWebServer()).getTomcat().getService().findConnectors();
        assertThat(connectors).hasSize(1);
        assertThat(connectors[0].getSecure()).isTrue();
        assertThat(connectors[0].getScheme()).isEqualTo("https");
        for (String protocol : new String[]{"TLSv1.2", "TLSv1.3"}) {
            var response = client(protocol).send(HttpRequest.newBuilder(uri("https", "/api/v1"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.sslSession().orElseThrow().getProtocol()).isEqualTo(protocol);
        }
        try {
            var plain = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("http", "/api/v1"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(plain.statusCode()).isEqualTo(400);
            assertThat(plain.headers().firstValue("Set-Cookie")).isEmpty();
        } catch (IOException rejectedTls) {
            // A connection rejected before any HTTP response is also a valid TLS-only outcome.
        }
    }
    @Test
    void secureCookiesAndHstsDoNotDependOnForwardedHeaders() throws Exception {
        var response = client("TLSv1.3").send(HttpRequest.newBuilder(uri("https", "/api/v1/identidad/csrf"))
                .header("X-Forwarded-Proto", "http").header("X-Forwarded-For", "192.0.2.10")
                .header("Forwarded", "proto=http;for=192.0.2.10").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Set-Cookie").orElseThrow()).contains("Secure", "HttpOnly", "SameSite=Lax");
        assertThat(response.headers().firstValue("Strict-Transport-Security").orElseThrow()).contains("max-age=");
        var denied = client("TLSv1.3").send(HttpRequest.newBuilder(uri("https", "/api/v1/identidad/sesion"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(denied.body()).contains("NO_AUTENTICADO");
    }
    @Test
    void rejectsAnUntrustedCaAndAnIncorrectServerName() throws Exception {
        assertThatThrownBy(() -> HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("https", "/api/v1"))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(SSLHandshakeException.class);
        try (var tcp = new Socket(address(), port);
             var socket = (SSLSocket) HttpsTestMaterial.INSTANCE.clientContext().getSocketFactory()
                     .createSocket(tcp, "wrong.example.test", port, true)) {
            socket.setSoTimeout(5000);
            var parameters = socket.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            assertThatThrownBy(socket::startHandshake).isInstanceOf(SSLHandshakeException.class);
        }
    }
}
