package software.mspi.evidencias.internal.persistence.tenant;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import software.mspi.evidencias.api.EvidenceFailure;

import static software.mspi.evidencias.api.EvidenceFailure.Reason.*;

record EvidenceContent(byte[] bytes, String type, String digest) {
    private static final int MAX_BYTES = 10_000_000;
    private static final int MAX_PIXELS = 20_000_000;
    private static final byte[] PNG_START = HexFormat.of().parseHex("89504e470d0a1a0a");
    private static final byte[] PNG_END = HexFormat.of().parseHex("0000000049454e44ae426082");
    private static final byte[] JPEG_START = HexFormat.of().parseHex("ffd8ff");
    private static final byte[] JPEG_END = HexFormat.of().parseHex("ffd9");
    private static final byte[] PDF_START = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PDF_END = "%%EOF".getBytes(StandardCharsets.US_ASCII);

    static EvidenceContent inspect(String name, InputStream input) {
        if (name == null || name.isBlank() || name.length() > 255 || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0 || name.codePoints().anyMatch(c ->
                        Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)) {
            throw new EvidenceFailure(INVALID_FILE);
        }
        String lower = name.toLowerCase(Locale.ROOT);
        try {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new EvidenceFailure(TOO_LARGE);
            if (lower.endsWith(".png") && starts(bytes, PNG_START) && ends(bytes, bytes.length, PNG_END)) {
                verifyImage(bytes, "PNG");
                return new EvidenceContent(bytes, "image/png", sha256(bytes));
            }
            if ((lower.endsWith(".jpg") || lower.endsWith(".jpeg"))
                    && starts(bytes, JPEG_START) && ends(bytes, bytes.length, JPEG_END)) {
                verifyImage(bytes, "JPEG");
                return new EvidenceContent(bytes, "image/jpeg", sha256(bytes));
            }
            if (lower.endsWith(".pdf") && starts(bytes, PDF_START) && ends(bytes, pdfEnd(bytes), PDF_END)) {
                verifyPdf(bytes);
                return new EvidenceContent(bytes, "application/pdf", sha256(bytes));
            }
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException malformed) {
            throw new EvidenceFailure(INVALID_FILE);
        }
        throw new EvidenceFailure(INVALID_FILE);
    }

    private static boolean starts(byte[] bytes, byte[] header) {
        return bytes.length >= header.length && Arrays.equals(bytes, 0, header.length, header, 0, header.length);
    }

    private static boolean ends(byte[] bytes, int length, byte[] trailer) {
        return length >= trailer.length && Arrays.equals(bytes, length - trailer.length, length, trailer, 0, trailer.length);
    }

    private static int pdfEnd(byte[] bytes) {
        int end = bytes.length;
        while (end > 0 && (bytes[end - 1] == ' ' || bytes[end - 1] == '\r' || bytes[end - 1] == '\n'
                || bytes[end - 1] == '\t' || bytes[end - 1] == '\f' || bytes[end - 1] == 0)) {
            end--;
        }
        return end;
    }

    private static void verifyPdf(byte[] bytes) throws IOException {
        try (var source = new RandomAccessReadBuffer(bytes); var document = new PDFParser(source).parse(false)) {
            if (document.isEncrypted() || document.getNumberOfPages() < 1) {
                throw new EvidenceFailure(INVALID_FILE);
            }
        }
    }

    private static void verifyImage(byte[] bytes, String format) throws IOException {
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var reader = ImageIO.getImageReadersByFormatName(format).next();
            try {
                var warned = new AtomicBoolean();
                reader.addIIOReadWarningListener((source, warning) -> warned.set(true));
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS
                        || reader.read(0) == null || warned.get()) {
                    throw new EvidenceFailure(INVALID_FILE);
                }
            } finally {
                reader.dispose();
            }
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
