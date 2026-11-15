package ua.vtc.rozklad.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.notification.config.MailChannelProperties;
import ua.vtc.rozklad.notification.config.MailConfig;
import ua.vtc.rozklad.notification.support.MailTestProperties;

/**
 * The SMTP adapter against a real SMTP server: Mailpit in Docker (the same catcher as the dev stack), read back
 * over its API. Nothing leaves the container.
 */
class SmtpMailTransportIT {

    static final DockerImageName MAILPIT = DockerImageName.parse("axllent/mailpit:v1.33.1");

    @SuppressWarnings("resource")
    private static final GenericContainer<?> CATCHER = new GenericContainer<>(MAILPIT)
            .withExposedPorts(1025, 8025)
            // a recipient outside this pattern is refused, so a permanent 5xx can be tested
            .withEnv("MP_SMTP_ALLOWED_RECIPIENTS", "@(vtc\\.vn\\.ua|rozklad\\.local)$")
            .waitingFor(Wait.forHttp("/readyz").forPort(8025).withStartupTimeout(Duration.ofMinutes(2)));

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SmtpMailTransport transport;

    @BeforeAll
    static void start() {
        CATCHER.start();
    }

    @AfterAll
    static void stop() {
        CATCHER.stop();
    }

    @BeforeEach
    void transport() throws Exception {
        HTTP.send(HttpRequest.newBuilder(api("/api/v1/messages")).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        MailChannelProperties properties = MailTestProperties.of("host", CATCHER.getHost(),
                "port", Integer.toString(CATCHER.getMappedPort(1025)), "starttls", "false",
                "from", "Розклад ВТФК <rozklad@rozklad.local>", "reply-to", "Навчальна частина <nch@vtc.vn.ua>",
                "connect-timeout", "5s", "read-timeout", "5s", "write-timeout", "5s");
        MailChannel channel = MailTestProperties.channel(properties, "http://localhost:8088");
        assertThat(channel.enabled()).isTrue();
        transport = new SmtpMailTransport(MailConfig.javaMailSender(properties), channel, Clock.systemUTC());
    }

    @Test
    void sendsOneUtf8AlternativeLetterWithThreadingAndNoAutoReplyHeaders() throws Exception {
        String id = transport.send(new OutgoingMail("k.mykola@vtc.vn.ua", "Микола Кравець",
                "Вам поставили заміну: пн, 16 листопада, 3 пара, 4КІ1", "<p>Аудиторія <b>214</b></p>",
                "Аудиторія: 214\n", "letter-1@rozklad.local", "change-42@rozklad.local",
                "http://localhost:8088/me/notifications"));
        assertThat(id).isEqualTo("letter-1@rozklad.local");

        MimeMessage message = onlyMessage();
        assertThat(message.getSubject()).isEqualTo("Вам поставили заміну: пн, 16 листопада, 3 пара, 4КІ1");
        assertThat(message.getContentType()).startsWith("multipart/alternative");
        Multipart parts = (Multipart) message.getContent();
        assertThat(parts.getCount()).isEqualTo(2);
        assertThat(parts.getBodyPart(0).getContentType()).startsWith("text/plain").containsIgnoringCase("utf-8");
        assertThat(parts.getBodyPart(0).getContent()).asString().isEqualTo("Аудиторія: 214\n");
        assertThat(parts.getBodyPart(1).getContentType()).startsWith("text/html").containsIgnoringCase("utf-8");
        assertThat(parts.getBodyPart(1).getContent()).asString().isEqualTo("<p>Аудиторія <b>214</b></p>");

        assertThat(message.getMessageID()).isEqualTo("<letter-1@rozklad.local>");
        assertThat(message.getHeader("In-Reply-To")).containsExactly("<change-42@rozklad.local>");
        assertThat(message.getHeader("References")).containsExactly("<change-42@rozklad.local>");
        assertThat(message.getHeader("Auto-Submitted")).containsExactly("auto-generated");
        assertThat(message.getHeader("X-Auto-Response-Suppress")).containsExactly("All");
        assertThat(message.getHeader("List-Unsubscribe")).containsExactly("<http://localhost:8088/me/notifications>");

        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("rozklad@rozklad.local");
        assertThat(from.getPersonal()).isEqualTo("Розклад ВТФК");
        assertThat(((InternetAddress) message.getReplyTo()[0]).getAddress()).isEqualTo("nch@vtc.vn.ua");
        Address[] to = message.getRecipients(Message.RecipientType.TO);
        assertThat(to).hasSize(1);
        assertThat(((InternetAddress) to[0]).getAddress()).isEqualTo("k.mykola@vtc.vn.ua");
        assertThat(((InternetAddress) to[0]).getPersonal()).isEqualTo("Микола Кравець");
        assertThat(message.getRecipients(Message.RecipientType.CC)).isNull();
    }

    @Test
    void dataCannotAddHeadersOrRecipients() throws Exception {
        transport.send(new OutgoingMail("k.mykola@vtc.vn.ua", "Ім'я\r\nBcc: evil@vtc.vn.ua",
                "Тема\r\nBcc: evil@vtc.vn.ua\r\nX-Evil: 1", "<p>html</p>", "text\n", "letter-2@rozklad.local", null,
                null));

        MimeMessage message = onlyMessage();
        assertThat(message.getSubject()).isEqualTo("Тема Bcc: evil@vtc.vn.ua X-Evil: 1");
        assertThat(message.getHeader("X-Evil")).isNull();
        assertThat(message.getHeader("Bcc")).isNull();
        assertThat(message.getHeader("References")).isNull();
        assertThat(message.getRecipients(Message.RecipientType.TO)).hasSize(1);
        JsonNode summary = messages().get("messages").get(0);
        assertThat(summary.path("To").size()).isOne();
        assertThat(summary.path("Bcc").size()).isZero();
    }

    @Test
    void aRefusedRecipientIsPermanent() {
        assertThatThrownBy(() -> transport.send(new OutgoingMail("someone@gmail.com", null, "Тема", "<p>x</p>",
                "x\n", "letter-3@rozklad.local", null, null)))
                .isInstanceOfSatisfying(MailTransportException.class, failure -> {
                    assertThat(failure.permanent()).isTrue();
                    assertThat(failure.smtpCode()).isBetween(500, 599);
                    assertThat(failure.getMessage()).startsWith("Сервер відхилив адресата").doesNotContain("someone@");
                });
    }

    @Test
    void anUnreachableServerIsTransient() {
        MailChannelProperties properties = MailTestProperties.of("host", "127.0.0.1", "port", "1",
                "starttls", "false", "from", "rozklad@rozklad.local", "connect-timeout", "2s");
        SmtpMailTransport nowhere = new SmtpMailTransport(MailConfig.javaMailSender(properties),
                MailTestProperties.channel(properties, "http://localhost:8088"), Clock.systemUTC());
        assertThatThrownBy(() -> nowhere.send(new OutgoingMail("k@vtc.vn.ua", null, "Тема", "<p>x</p>", "x\n",
                "letter-4@rozklad.local", null, null)))
                .isInstanceOfSatisfying(MailTransportException.class, failure -> {
                    assertThat(failure.permanent()).isFalse();
                    assertThat(failure.configProblem()).isFalse();
                });
    }

    private static MimeMessage onlyMessage() throws Exception {
        JsonNode list = messages();
        assertThat(list.get("total").asInt()).isEqualTo(1);
        String id = list.get("messages").get(0).get("ID").asString();
        HttpResponse<byte[]> raw = HTTP.send(HttpRequest.newBuilder(api("/api/v1/message/" + id + "/raw")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(raw.statusCode()).isEqualTo(200);
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(raw.body()));
    }

    private static JsonNode messages() throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(api("/api/v1/messages")).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static URI api(String path) {
        return URI.create("http://" + CATCHER.getHost() + ":" + CATCHER.getMappedPort(8025) + path);
    }
}
