package software.mspi.gestion.internal.web;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class HttpsDeploymentConfiguration {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> validateHttpsDeployment(Environment environment) {
        return factory -> validate(environment);
    }

    static void validate(Environment environment) {
        var profiles = Arrays.stream(environment.getActiveProfiles()).filter(value -> value.startsWith("https-")).toList();
        if (profiles.isEmpty()) return;
        if (profiles.size() != 1 || !Set.of("https-local", "https-lan", "https-internet").contains(profiles.get(0))) {
            throw invalid("Seleccione un unico perfil HTTPS conocido.");
        }
        if (!environment.getProperty("server.ssl.enabled", Boolean.class, false)
                || !environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)
                || !environment.getProperty("server.servlet.session.cookie.http-only", Boolean.class, false)
                || !"none".equalsIgnoreCase(environment.getProperty("server.forward-headers-strategy"))) {
            throw invalid("HTTPS exige TLS, cookies protegidas y cabeceras de intermediario deshabilitadas.");
        }
        var protocols = Binder.get(environment).bind("server.ssl.enabled-protocols", Bindable.listOf(String.class)).orElse(List.of());
        if (protocols.isEmpty() || !Set.of("TLSv1.2", "TLSv1.3").containsAll(protocols)
                || !"PKCS12".equalsIgnoreCase(environment.getProperty("server.ssl.key-store-type"))
                || !"mspi-server".equals(environment.getProperty("server.ssl.key-alias"))
                || !"TLS".equals(environment.getProperty("server.ssl.protocol"))
                || environment.getProperty("server.ssl.bundle") != null) {
            throw invalid("Configure protocolos TLS modernos y el almacen PKCS12 previsto.");
        }
        String address = environment.getProperty("server.address", "");
        boolean lan = "https-lan".equals(profiles.get(0));
        if (lan ? !privateIpv4(address) : !"127.0.0.1".equals(address)) {
            throw invalid("Local e Internet exigen loopback; LAN exige una IPv4 privada explicita.");
        }
        String managementPort = environment.getProperty("management.server.port");
        if (managementPort != null && !managementPort.equals(environment.getProperty("server.port"))) {
            throw invalid("No se permite otro puerto de administracion en los perfiles HTTPS.");
        }
        validateCertificate(environment, lan ? address : "127.0.0.1");
    }

    private static boolean privateIpv4(String address) {
        if (!address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        int[] parts = Arrays.stream(address.split("\\.")).mapToInt(Integer::parseInt).toArray();
        if (Arrays.stream(parts).anyMatch(value -> value > 255)) return false;
        return parts[0] == 10 || parts[0] == 192 && parts[1] == 168
                || parts[0] == 172 && parts[1] >= 16 && parts[1] <= 31;
    }

    private static void validateCertificate(Environment environment, String address) {
        char[] password = environment.getProperty("server.ssl.key-store-password", "").toCharArray();
        try {
            URI location = URI.create(environment.getRequiredProperty("server.ssl.key-store"));
            if (!"file".equalsIgnoreCase(location.getScheme()) || password.length < 16) {
                throw invalid("Se requiere un almacen externo y una clave TLS no vacia y protegida.");
            }
            var store = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(Path.of(location))) { store.load(input, password); }
            if (!store.isKeyEntry("mspi-server")) throw invalid("No se encontro la identidad TLS del servidor.");
            var certificate = (X509Certificate) store.getCertificate("mspi-server");
            certificate.checkValidity();
            if (certificate.getExtendedKeyUsage() == null
                    || !certificate.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.1")
                    || certificate.getSubjectAlternativeNames() == null
                    || certificate.getSubjectAlternativeNames().stream().noneMatch(name ->
                        Integer.valueOf(7).equals(name.get(0)) && address.equals(name.get(1)))) {
                throw invalid("El certificado debe permitir servidor TLS e incluir la IP de escucha.");
            }
        } catch (Exception failure) {
            throw invalid("No se pudo validar el certificado TLS externo, su vigencia o su nombre.");
        } finally { Arrays.fill(password, '\0'); }
    }

    private static IllegalStateException invalid(String message) { return new IllegalStateException(message); }
}
