package software.mspi.identidad.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Offline policy for new credentials; never applied retroactively during login. */
public final class PasswordPolicy {
    public static final int MIN_LENGTH = 15;
    public static final int MAX_LENGTH = 1024;
    private static final Set<String> BLOCKED = load();

    private PasswordPolicy() {}

    public static boolean validMinimum(int minimum) {
        return minimum >= MIN_LENGTH && minimum <= MAX_LENGTH;
    }

    public static boolean accepts(CharSequence candidate, int minimum) {
        if (!validMinimum(minimum) || candidate == null || candidate.length() > MAX_LENGTH) {
            return false;
        }
        String value = candidate.toString();
        if (value.codePointCount(0, value.length()) < minimum || value.isBlank()) {
            return false;
        }
        // Compare the entire value; hashing for storage keeps its original case and spaces.
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
            return !BLOCKED.contains(digest);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 no disponible.", failure);
        }
    }

    private static Set<String> load() {
        var resource = PasswordPolicy.class.getResourceAsStream("/security/blocked-passwords.sha256");
        if (resource == null) {
            throw new IllegalStateException("Lista de contrasenas bloqueadas no disponible.");
        }
        try (var reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.US_ASCII))) {
            var values = reader.lines().collect(Collectors.toUnmodifiableSet());
            if (values.size() != 329 || values.stream().anyMatch(value -> !value.matches("[0-9a-f]{64}"))) {
                throw new IllegalStateException("Lista de contrasenas bloqueadas invalida.");
            }
            return values;
        } catch (IOException failure) {
            throw new IllegalStateException("No se pudo cargar la lista de contrasenas bloqueadas.", failure);
        }
    }
}
