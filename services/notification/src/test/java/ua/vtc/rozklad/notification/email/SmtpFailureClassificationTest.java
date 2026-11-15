package ua.vtc.rozklad.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import ua.vtc.rozklad.notification.config.MailChannelProperties;
import ua.vtc.rozklad.notification.email.EmailRetryPolicy.Decision;
import ua.vtc.rozklad.notification.support.MailTestProperties;

/**
 * How an SMTP refusal is read, with the exceptions Angus Mail really throws (wrapped by Spring as it does): only a
 * refused recipient fails a letter at once; a refused sender is our own setup and every letter waits for the fix.
 */
class SmtpFailureClassificationTest {

    private static final String TO = "k.mykola@vtc.vn.ua";

    private final MailChannelProperties properties = MailTestProperties.of("host", "localhost", "starttls", "false",
            "from", "rozklad@vtc.vn.ua");
    private final EmailRetryPolicy retry = new EmailRetryPolicy(properties);

    @Test
    void aRefusedSenderIsAConfigurationProblemAndIsRetried() throws Exception {
        // Angus: MAIL FROM answered 553 → SMTPSendFailedException with an SMTPSenderFailedException next to it
        SMTPSendFailedException mailFrom = new SMTPSendFailedException("MAIL FROM:<rozklad@vtc.vn.ua>", 553,
                "553 5.7.1 Sender address rejected: not owned by user", null, new Address[0], new Address[0], null);
        mailFrom.setNextException(new SMTPSenderFailedException(new InternetAddress("rozklad@vtc.vn.ua"),
                "MAIL FROM:<rozklad@vtc.vn.ua>", 553, "553 5.7.1 Sender address rejected"));

        MailTransportException failure = sendFailingWith(mailFrom);

        assertThat(failure.configProblem()).isTrue();
        assertThat(failure.permanent()).isFalse();
        assertThat(failure.smtpCode()).isEqualTo(553);
        assertThat(failure.getMessage()).startsWith("SMTP-сервер не прийняв адресу відправника (MAIL_FROM)")
                .doesNotContain("rozklad@vtc.vn.ua");
        assertThat(retry.onSendFailure(failure, 1, Instant.EPOCH)).isInstanceOf(Decision.Retry.class);
    }

    @Test
    void aRelayDeniedAtMailFromWithoutTheSenderDetailIsAConfigurationProblemToo() {
        SMTPSendFailedException relay = new SMTPSendFailedException("MAIL FROM:<rozklad@vtc.vn.ua>", 550,
                "550 5.7.1 Invalid credentials for relay", null, new Address[0], new Address[0], null);

        MailTransportException failure = sendFailingWith(relay);

        assertThat(failure.configProblem()).isTrue();
        assertThat(failure.permanent()).isFalse();
    }

    @Test
    void aRefusedRecipientFailsTheLetterAtOnce() throws Exception {
        InternetAddress to = new InternetAddress(TO);
        SendFailedException invalid = new SendFailedException("Invalid Addresses",
                new SMTPAddressFailedException(to, "RCPT TO:<" + TO + ">", 550, "550 5.1.1 User unknown"),
                new Address[0], new Address[0], new Address[]{to});

        MailTransportException failure = sendFailingWith(invalid);

        assertThat(failure.permanent()).isTrue();
        assertThat(failure.configProblem()).isFalse();
        assertThat(failure.smtpCode()).isEqualTo(550);
        assertThat(failure.getMessage()).startsWith("Сервер відхилив адресата").doesNotContain(TO);
        assertThat(retry.onSendFailure(failure, 1, Instant.EPOCH)).isInstanceOf(Decision.Fail.class);
    }

    @Test
    void aRefusalAfterDataIsRetriedNotTakenForTheRecipient() throws Exception {
        // the recipient was accepted, the server refused the message itself
        SMTPSendFailedException data = new SMTPSendFailedException(".", 554, "554 5.7.0 Message rejected", null,
                new Address[]{new InternetAddress(TO)}, new Address[0], new Address[0]);

        MailTransportException failure = sendFailingWith(data);

        assertThat(failure.permanent()).isFalse();
        assertThat(failure.configProblem()).isFalse();
        assertThat(failure.getMessage()).startsWith("SMTP не прийняв лист");
    }

    private MailTransportException sendFailingWith(MessagingException cause) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage message) {
                Map<Object, Exception> failed = new LinkedHashMap<>();
                failed.put(message, cause);
                throw new MailSendException(failed);
            }
        };
        SmtpMailTransport transport = new SmtpMailTransport(sender,
                MailTestProperties.channel(properties, "http://localhost:8088"), Clock.systemUTC());
        Throwable thrown = catchThrowable(() -> transport.send(new OutgoingMail(TO,
                null, "Тема", "<p>x</p>", "x\n", "letter-1@rozklad.local", null, null)));
        assertThat(thrown).isInstanceOf(MailTransportException.class);
        return (MailTransportException) thrown;
    }

    @Test
    void aFailureWithoutAnSmtpReplyIsRetried() {
        MailTransportException failure = sendFailingWith(new MessagingException("Could not connect to SMTP host"));

        assertThat(failure.permanent()).isFalse();
        assertThat(failure.configProblem()).isFalse();
    }
}
