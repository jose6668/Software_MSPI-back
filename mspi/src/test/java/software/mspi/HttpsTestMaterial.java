package software.mspi;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;

/** Ephemeral test material; CI uses the same certificate generator shipped to operators. */
public final class HttpsTestMaterial {
    public static final HttpsTestMaterial INSTANCE = create();
    public final Path directory;
    public final String password;
    public final String lanAddress;

    private HttpsTestMaterial(Path directory, String password, String lanAddress) {
        this.directory = directory; this.password = password; this.lanAddress = lanAddress;
    }
    public String keyStoreUri() { return directory.resolve("server.p12").toUri().toString(); }
    public SSLContext clientContext() throws Exception {
        var trust = KeyStore.getInstance("PKCS12"); trust.load(null, null);
        try (var input = Files.newInputStream(directory.resolve("ca.crt"))) {
            trust.setCertificateEntry("mspi-test-ca", CertificateFactory.getInstance("X.509").generateCertificate(input));
        }
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(trust);
        var context = SSLContext.getInstance("TLS"); context.init(null, managers.getTrustManagers(), null); return context;
    }
    private static HttpsTestMaterial create() {
        try {
            String supplied = System.getenv("MSPI_TEST_TLS_DIRECTORY");
            String lan = System.getenv("MSPI_TEST_TLS_LAN_ADDRESS");
            if (lan == null) {
                lan = NetworkInterface.networkInterfaces().flatMap(NetworkInterface::inetAddresses)
                        .filter(address -> address instanceof Inet4Address && address.isSiteLocalAddress())
                        .map(address -> address.getHostAddress()).findFirst()
                        .orElseThrow(() -> new IllegalStateException("La prueba LAN requiere una IPv4 privada local."));
            }
            if (supplied != null) {
                Path path = Path.of(supplied);
                return new HttpsTestMaterial(path, Files.readString(path.resolve("server-password.txt")).trim(), lan);
            }
            // Local Maven runs only need the JDK, not PowerShell or an external CA service.
            Path path = Files.createTempDirectory("mspi-https-test-");
            String password = UUID.randomUUID().toString();
            var fixture = new HttpsTestMaterial(path, password, lan);
            fixture.keytool(List.of("-genkeypair", "-alias", "mspi-server", "-keyalg", "RSA", "-keysize", "3072",
                    "-validity", "1", "-dname", "CN=localhost", "-ext", "EKU=serverAuth", "-ext",
                    "SAN=DNS:localhost,IP:127.0.0.1,IP:" + lan, "-keystore", path.resolve("server.p12").toString(),
                    "-storetype", "PKCS12", "-storepass:env", "MSPI_TEST_CERT_PASSWORD"));
            fixture.keytool(List.of("-exportcert", "-rfc", "-alias", "mspi-server", "-keystore",
                    path.resolve("server.p12").toString(), "-storepass:env", "MSPI_TEST_CERT_PASSWORD",
                    "-file", path.resolve("ca.crt").toString()));
            path.toFile().deleteOnExit(); path.resolve("server.p12").toFile().deleteOnExit();
            path.resolve("ca.crt").toFile().deleteOnExit();
            return fixture;
        } catch (Exception failure) { throw new IllegalStateException("No se pudo preparar el TLS desechable de pruebas."); }
    }
    private void keytool(List<String> arguments) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString()); command.addAll(arguments);
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("MSPI_TEST_CERT_PASSWORD", password);
        var process = builder.start();
        process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly(); throw new IllegalStateException("Fallo keytool para TLS desechable.");
        }
    }
}
