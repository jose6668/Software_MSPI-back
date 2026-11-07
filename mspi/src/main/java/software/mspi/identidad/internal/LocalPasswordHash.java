package software.mspi.identidad.internal;

import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.Map;

import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

/** Offline utility for the authorized operator preparing a local account. */
public final class LocalPasswordHash {

    private static final String ID = "pbkdf2-sha256-v1";

    private LocalPasswordHash() {}

    static PasswordEncoder encoder() {
        var encoder = new Pbkdf2PasswordEncoder("", 16, 600_000,
                Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        return new DelegatingPasswordEncoder(ID, Map.of(ID, encoder));
    }

    public static void main(String[] args) {
        if (args.length != 0 || System.console() == null) {
            System.err.println("Use una terminal interactiva y suministre la contraseña mediante el prompt.");
            System.exit(2);
            return;
        }
        char[] password = System.console().readPassword("Contraseña local: ");
        if (password == null) {
            System.exit(2);
            return;
        }
        try {
            if (password.length == 0 || password.length > 1024) {
                System.err.println("Entrada de contraseña inválida.");
                System.exit(2);
            } else {
                System.console().writer().println(encoder().encode(CharBuffer.wrap(password)));
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
