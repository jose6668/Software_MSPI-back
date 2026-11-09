package software.mspi.activos.api;

import java.text.Normalizer;
import java.util.Locale;

public final class BusinessCode {
    private BusinessCode() {}

    public static String normalize(String code) {
        return Normalizer.normalize(code.strip().toUpperCase(Locale.ROOT), Normalizer.Form.NFC);
    }
}
