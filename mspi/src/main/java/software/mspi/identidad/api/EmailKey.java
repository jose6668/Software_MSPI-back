package software.mspi.identidad.api;

import java.util.Locale;

public final class EmailKey {

    private EmailKey() {}

    public static String normalize(String email) {
        return email.toLowerCase(Locale.ROOT);
    }
}
