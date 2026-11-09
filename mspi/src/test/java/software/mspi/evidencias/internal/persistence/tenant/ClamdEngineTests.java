package software.mspi.evidencias.internal.persistence.tenant;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

class ClamdEngineTests {
    @Test
    void realEngineDetectsHarmlessTestSignatureOverTheProductionWireFormat() throws Exception {
        byte[] marker = "MSPI-AV-TEST-MARKER".getBytes(StandardCharsets.US_ASCII);
        String signature = "MSPI.Test.Marker:0:*:" + HexFormat.of().formatHex(marker) + "\n";
        // Same limits as deployment; a synthetic catalog keeps CI independent of the update CDN.
        String configuration = Files.readString(Path.of("..", "deploy", "antimalware", "clamd.conf"))
                .replace("OfficialDatabaseOnly yes", "OfficialDatabaseOnly no");
        var image = DockerImageName.parse("clamav/clamav@sha256:04e5b7c0eac436c589073f5eb2924c322658e75498bf39906e89f9b08307081d");
        try (var clamd = new GenericContainer<>(image)
                .withCreateContainerCmdModifier(command -> {
                    command.withEntrypoint("clamd").withUser("clamav");
                    command.getHostConfig().withPortBindings(new PortBinding(Ports.Binding.bindIp("127.0.0.1"),
                            ExposedPort.tcp(3310))).withMemory(256L * 1024 * 1024).withPidsLimit(64L);
                })
                .withExposedPorts(3310)
                .withCopyToContainer(Transferable.of(configuration), "/tmp/mspi-clamd.conf")
                .withCopyToContainer(Transferable.of(signature), "/var/lib/clamav/mspi-test.ndb")
                .withCommand("--foreground", "--config-file=/tmp/mspi-clamd.conf")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)))) {
            clamd.start();
            assertThat(exchange(clamd, "contenido de prueba".getBytes(StandardCharsets.UTF_8))).isEqualTo("stream: OK");
            assertThat(exchange(clamd, marker)).startsWith("stream: MSPI.Test.Marker").endsWith(" FOUND");
        }
    }

    private static String exchange(GenericContainer<?> clamd, byte[] bytes) throws Exception {
        try (var socket = new Socket(clamd.getHost(), clamd.getMappedPort(3310))) {
            socket.setSoTimeout(3000);
            return EvidenceAntimalware.exchangeCommand(socket, bytes);
        }
    }
}
