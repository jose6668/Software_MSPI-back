package software.mspi.identidad.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import software.mspi.identidad.api.AccountRecovery;
import software.mspi.identidad.api.EmailKey;
import software.mspi.identidad.api.LoginLimitException;
import software.mspi.identidad.api.RecoveryFailure;

@Service
@ConditionalOnProperty(name = "mspi.recovery.enabled", havingValue = "true")
final class JdbcAccountRecovery implements AccountRecovery {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcAccountRecovery.class);

    private final JdbcClient jdbc;
    private final RecoverySettings settings;
    private final PasswordEncoder passwords;
    private final MailSender mail;
    private final TaskExecutor deliveryExecutor;
    private final TransactionTemplate transactions;
    private final AttemptLimiter requests;
    private final AttemptLimiter confirmations;
    private final Semaphore checks;
    private final SecureRandom random = new SecureRandom();

    JdbcAccountRecovery(JdbcClient jdbc, RecoverySettings settings, PasswordEncoder passwords, MailSender mail,
                        @Qualifier("recoveryDeliveryExecutor") TaskExecutor deliveryExecutor,
                        @Qualifier("commonTransactionManager") PlatformTransactionManager transactionManager,
                        ObjectProvider<Clock> clocks) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.passwords = passwords;
        this.mail = mail;
        this.deliveryExecutor = deliveryExecutor;
        this.transactions = new TransactionTemplate(transactionManager);
        Clock clock = clocks.getIfAvailable(Clock::systemUTC);
        this.requests = limiter(settings, clock);
        this.confirmations = limiter(settings, clock);
        this.checks = new Semaphore(settings.concurrentChecks());
    }

    @Override
    public void request(String email, String address, UUID correlation) {
        String key = EmailKey.normalize(email);
        requests.reserve(key, address);
        try {
            deliveryExecutor.execute(() -> issue(key, correlation));
        } catch (RejectedExecutionException full) {
            throw new LoginLimitException();
        }
    }

    @Override
    public void confirm(String token, String password, String address) {
        byte[] fingerprint = fingerprint(token);
        confirmations.reserve(HexFormat.of().formatHex(fingerprint), address);
        if (!checks.tryAcquire()) {
            throw new LoginLimitException();
        }
        try {
            boolean eligible = jdbc.sql("SELECT comun.puede_confirmar_recuperacion(:fingerprint)")
                    .param("fingerprint", fingerprint).query(Boolean.class).single();
            if (!eligible) {
                throw unavailable();
            }
            if (password.length() < settings.minPasswordLength()) {
                throw new RecoveryFailure(RecoveryFailure.Reason.PASSWORD_LENGTH);
            }
            boolean confirmed = jdbc.sql("SELECT comun.confirmar_recuperacion(:fingerprint, :hash)")
                    .param("fingerprint", fingerprint).param("hash", passwords.encode(password))
                    .query(Boolean.class).single();
            if (!confirmed) {
                throw unavailable();
            }
        } finally {
            checks.release();
        }
    }

    private void issue(String key, UUID correlation) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            transactions.executeWithoutResult(status -> {
                var recipient = jdbc.sql("SELECT comun.emitir_recuperacion(:key, :fingerprint, :seconds)")
                        .param("key", key).param("fingerprint", fingerprint(token))
                        .param("seconds", Math.toIntExact(settings.validity().toSeconds()))
                        .query(String.class).optional();
                recipient.ifPresent(email -> send(email, token));
            });
        } catch (DataAccessException | MailException | TransactionException failure) {
            LOG.warn("No se pudo emitir el comprobante de recuperación correlationId={}", correlation);
        }
    }

    private void send(String email, String token) {
        var message = new SimpleMailMessage();
        message.setFrom(settings.from());
        message.setTo(email);
        message.setSubject("Recuperación de acceso a MSPI");
        message.setText("Abra este enlace para restablecer su contraseña de MSPI:\n"
                + settings.resetUrl().toASCIIString() + "#token=" + token
                + "\nSi no solicitó recuperar su acceso, puede ignorar el mensaje.");
        mail.send(message);
    }

    private static AttemptLimiter limiter(RecoverySettings settings, Clock clock) {
        return new AttemptLimiter(settings.attemptWindow(), settings.attemptsPerKey(), settings.attemptsPerAddress(),
                settings.totalAttempts(), clock);
    }

    private static byte[] fingerprint(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 no disponible.", failure);
        }
    }

    private static RecoveryFailure unavailable() {
        return new RecoveryFailure(RecoveryFailure.Reason.UNAVAILABLE);
    }
}
