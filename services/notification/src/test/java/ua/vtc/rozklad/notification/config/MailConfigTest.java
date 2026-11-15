package ua.vtc.rozklad.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import ua.vtc.rozklad.notification.email.DisabledMailTransport;
import ua.vtc.rozklad.notification.email.SmtpMailTransport;
import ua.vtc.rozklad.notification.support.MailTestProperties;

/** An SMTP sender exists only for a configured channel, with TLS, timeouts and never {@code mail.debug}. */
class MailConfigTest {

    private final MailConfig config = new MailConfig();

    @Test
    void anEmptyHostCreatesNoSmtpSender() {
        assertThat(config.mailTransport(MailTestProperties.channel(MailTestProperties.of("host", ""),
                "https://rozklad.vtc.vn.ua"), Clock.systemUTC())).isInstanceOf(DisabledMailTransport.class);
        // refused by the guard: a development stand with a real server
        assertThat(config.mailTransport(MailTestProperties.channel(MailTestProperties.of("host", "smtp.gmail.com",
                "from", "rozklad@vtc.vn.ua"), "http://localhost:8088"), Clock.systemUTC()))
                .isInstanceOf(DisabledMailTransport.class);
        assertThat(config.mailTransport(MailTestProperties.channel(MailTestProperties.of("host", "smtp.gmail.com",
                "from", "rozklad@vtc.vn.ua"), "https://rozklad.vtc.vn.ua"), Clock.systemUTC()))
                .isInstanceOf(SmtpMailTransport.class);
    }

    @Test
    void starttlsWithAuthAndTimeoutsOnPort587() {
        JavaMailSenderImpl sender = MailConfig.javaMailSender(MailTestProperties.of("host", " smtp.gmail.com ",
                "username", "rozklad@vtc.vn.ua", "password", "app-password"));
        assertThat(sender.getHost()).isEqualTo("smtp.gmail.com");
        assertThat(sender.getPort()).isEqualTo(587);
        assertThat(sender.getProtocol()).isEqualTo("smtp");
        assertThat(sender.getJavaMailProperties())
                .containsEntry("mail.smtp.auth", "true")
                .containsEntry("mail.smtp.starttls.enable", "true")
                .containsEntry("mail.smtp.starttls.required", "true")
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
                .containsEntry("mail.smtp.connectiontimeout", "10000")
                .containsEntry("mail.smtp.timeout", "20000")
                .containsEntry("mail.smtp.writetimeout", "20000")
                .containsEntry("mail.debug", "false");
    }

    @Test
    void implicitTlsOnPort465AndNoAuthForTheCatcher() {
        JavaMailSenderImpl smtps = MailConfig.javaMailSender(MailTestProperties.of("host", "smtp.gmail.com",
                "port", "465", "ssl", "true", "starttls", "false", "username", "u@vtc.vn.ua", "password", "p"));
        assertThat(smtps.getProtocol()).isEqualTo("smtps");
        assertThat(smtps.getJavaMailProperties()).containsEntry("mail.smtps.auth", "true")
                .containsEntry("mail.smtps.ssl.checkserveridentity", "true")
                .containsEntry("mail.smtp.starttls.enable", "false");

        JavaMailSenderImpl catcher = MailConfig.javaMailSender(MailTestProperties.of("host", "mailpit",
                "port", "1025", "starttls", "false"));
        assertThat(catcher.getUsername()).isNull();
        assertThat(catcher.getJavaMailProperties()).containsEntry("mail.smtp.auth", "false")
                .containsEntry("mail.smtp.starttls.required", "false");
    }
}
