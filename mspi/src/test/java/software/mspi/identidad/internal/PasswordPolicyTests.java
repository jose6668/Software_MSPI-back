package software.mspi.identidad.internal;

import java.net.URI;
import java.nio.CharBuffer;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.mspi.identidad.api.PasswordPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTests {
    @ParameterizedTest
    @ValueSource(strings = {"123456789012345", "12345678901234567890", "passwordpassword",
            "PASSWORDPASSWORD", "111111111111111", "muy corta", "               "})
    void rejectsShortBlankAndKnownExposedValues(String candidate) {
        assertThat(PasswordPolicy.accepts(candidate, 15)).isFalse();
        assertThat(PasswordPolicy.accepts(CharBuffer.wrap(candidate), 15)).isFalse();
    }

    @Test
    void countsCodePointsAndKeepsSpacesAndLongPhrases() {
        String phrase = "una frase larga con espacios";
        assertThat(PasswordPolicy.accepts(phrase, 15)).isTrue();
        assertThat(PasswordPolicy.accepts("un secreto de x", 15)).isTrue();
        assertThat(PasswordPolicy.accepts("un secreto de x", 16)).isFalse();
        assertThat(PasswordPolicy.accepts("\uD83D\uDE80".repeat(8), 15)).isFalse();
        assertThat(PasswordPolicy.accepts("una frase larga con espacios".repeat(3), 15)).isTrue();
        assertThat(PasswordPolicy.accepts("x".repeat(1025), 15)).isFalse();
        assertThat(PasswordPolicy.accepts(null, 15)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 14, 1025})
    void refusesWeakOrImpossibleRecoveryConfiguration(int minimum) {
        assertThatThrownBy(() -> new RecoverySettings(Duration.ofMinutes(20),
                URI.create("https://example.test/reset"), "mspi@example.test", minimum,
                Duration.ofMinutes(1), 4, 40, 100, 4, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThat(PasswordPolicy.accepts("una frase larga con espacios", minimum)).isFalse();
    }

    @Test
    void invitationEncoderCannotBypassPolicyAndDoesNotEchoRejectedSecret() {
        var settings = new IdentitySettings(Duration.ofHours(8), Duration.ofMinutes(1), 5, 30, 120, 4);
        var passwords = new LocalInvitedAccountPassword(LocalPasswordHash.encoder(), settings);
        String blocked = "passwordpassword";
        assertThatThrownBy(() -> passwords.encode(blocked)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(blocked);
        String accepted = "una frase larga para mi cuenta";
        assertThat(LocalPasswordHash.encoder().matches(accepted, passwords.encode(accepted))).isTrue();
    }
}
