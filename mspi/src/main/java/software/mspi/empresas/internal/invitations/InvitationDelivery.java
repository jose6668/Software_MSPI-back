package software.mspi.empresas.internal.invitations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Component;
import software.mspi.empresas.api.InvitationFailure;

@Component
@ConditionalOnProperty(name = "mspi.invitations.enabled", havingValue = "true")
public final class InvitationDelivery {

    private final MailSender mail;
    private final InvitationSettings settings;
    private final SecureRandom random = new SecureRandom();

    InvitationDelivery(MailSender mail, InvitationSettings settings) {
        this.mail = mail;
        this.settings = settings;
    }

    public Pending create() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new Pending(UUID.randomUUID(), token, Math.toIntExact(settings.validity().toSeconds()));
    }

    public void send(String email, Pending invitation) {
        var message = new SimpleMailMessage();
        message.setFrom(settings.from());
        message.setTo(email);
        message.setSubject("Invitación a MSPI");
        message.setText("Abra este enlace para aceptar su invitación a MSPI:\n"
                + settings.acceptanceUrl().toASCIIString() + "#token=" + invitation.token()
                + "\nSi no esperaba esta invitación, puede ignorar el mensaje.");
        try {
            mail.send(message);
        } catch (MailException failure) {
            throw new InvitationFailure(InvitationFailure.Reason.DELIVERY);
        }
    }

    static byte[] fingerprint(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 no disponible.", failure);
        }
    }

    public record Pending(UUID id, String token, int validitySeconds) {
        public byte[] digest() { return fingerprint(token); }
        @Override public String toString() { return "Pending[redacted]"; }
    }
}
