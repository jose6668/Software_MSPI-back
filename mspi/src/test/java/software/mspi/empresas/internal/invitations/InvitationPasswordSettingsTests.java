package software.mspi.empresas.internal.invitations;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvitationPasswordSettingsTests {
    @ParameterizedTest
    @ValueSource(ints = {1, 14, 1025})
    void cannotConfigureAWeakerMinimum(int minimum) {
        assertThatThrownBy(() -> new InvitationSettings(Duration.ofDays(3),
                URI.create("https://example.test/invite"), "mspi@example.test", minimum))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
