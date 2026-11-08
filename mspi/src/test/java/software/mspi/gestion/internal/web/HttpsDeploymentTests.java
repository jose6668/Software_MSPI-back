package software.mspi.gestion.internal.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import software.mspi.HttpsTestMaterial;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpsDeploymentTests {
    private MockEnvironment configured(String profile) {
        var material = HttpsTestMaterial.INSTANCE;
        var env = new MockEnvironment().withProperty("server.ssl.enabled", "true")
                .withProperty("server.servlet.session.cookie.secure", "true")
                .withProperty("server.servlet.session.cookie.http-only", "true")
                .withProperty("server.forward-headers-strategy", "none")
                .withProperty("server.ssl.enabled-protocols", "TLSv1.2,TLSv1.3")
                .withProperty("server.ssl.protocol", "TLS")
                .withProperty("server.ssl.key-store-type", "PKCS12")
                .withProperty("server.ssl.key-alias", "mspi-server")
                .withProperty("server.ssl.key-store", material.keyStoreUri())
                .withProperty("server.ssl.key-store-password", material.password)
                .withProperty("server.port", "8443")
                .withProperty("server.address", profile.equals("https-lan") ? material.lanAddress : "127.0.0.1");
        env.setActiveProfiles(profile); return env;
    }
    @ParameterizedTest
    @ValueSource(strings = {"https-local", "https-lan", "https-internet"})
    void validatesEachProfileWithItsCertificate(String profile) {
        assertThatCode(() -> HttpsDeploymentConfiguration.validate(configured(profile))).doesNotThrowAnyException();
    }
    @ParameterizedTest
    @CsvSource({"server.ssl.enabled,false", "server.servlet.session.cookie.secure,false",
            "server.servlet.session.cookie.http-only,false", "server.forward-headers-strategy,framework",
            "server.ssl.enabled-protocols,TLSv1.1", "server.ssl.protocol,SSL", "server.ssl.key-store-type,JKS",
            "server.ssl.key-alias,other", "server.ssl.bundle,other", "server.address,0.0.0.0",
            "management.server.port,9443", "server.ssl.key-store,classpath:server.p12"})
    void rejectsInsecureOverridesBeforeOpeningTheEndpoint(String key, String value) {
        assertThatThrownBy(() -> HttpsDeploymentConfiguration.validate(configured("https-local").withProperty(key, value)))
                .isInstanceOf(IllegalStateException.class);
    }
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "0.0.0.0", "8.8.8.8"})
    void lanCannotAccidentallyListenOnWildcardPublicOrLoopbackAddress(String address) {
        assertThatThrownBy(() -> HttpsDeploymentConfiguration.validate(configured("https-lan")
                .withProperty("server.address", address))).isInstanceOf(IllegalStateException.class);
    }
    @Test
    void rejectsUnknownAndConflictingProfiles() {
        var env = configured("https-unknown");
        assertThatThrownBy(() -> HttpsDeploymentConfiguration.validate(env)).isInstanceOf(IllegalStateException.class);
        env.setActiveProfiles("https-local", "https-lan");
        assertThatThrownBy(() -> HttpsDeploymentConfiguration.validate(env)).isInstanceOf(IllegalStateException.class);
    }
    @Test
    void certificateFailuresNeverEchoTheKeystorePassword() {
        String secret = "only-a-test-secret-without-real-access";
        assertThatThrownBy(() -> HttpsDeploymentConfiguration.validate(configured("https-local")
                .withProperty("server.ssl.key-store-password", secret))).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(secret);
    }
}
