package software.mspi;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "https-local"})
@Import(PostgresTestConfiguration.class)
class HttpsLocalIntegrationTests extends AbstractHttpsIntegrationTests {
    @org.junit.jupiter.api.Test
    void insecureOverrideFailsApplicationStartup() {
        var material = HttpsTestMaterial.INSTANCE;
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> {
            try (var unexpected = new org.springframework.boot.builder.SpringApplicationBuilder(
                    MspiApplication.class, PostgresTestConfiguration.class)
                    .profiles("test", "https-local")
                    .properties(java.util.Map.of("MSPI_TLS_KEYSTORE", material.keyStoreUri(),
                            "MSPI_TLS_KEYSTORE_PASSWORD", material.password))
                    .run("--server.port=0", "--server.ssl.enabled=false")) {
                throw new AssertionError("No debe arrancar con TLS desactivado.");
            }
        }).hasRootCauseMessage("HTTPS exige TLS, cookies protegidas y cabeceras de intermediario deshabilitadas.");
    }
}
