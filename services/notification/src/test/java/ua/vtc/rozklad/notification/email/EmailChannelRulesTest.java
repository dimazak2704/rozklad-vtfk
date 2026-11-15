package ua.vtc.rozklad.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.notification.config.MailChannelProperties;
import ua.vtc.rozklad.notification.config.MailSafetyGuard;
import ua.vtc.rozklad.notification.config.MailSafetyGuard.Mode;
import ua.vtc.rozklad.notification.email.EmailRetryPolicy.Decision;
import ua.vtc.rozklad.notification.support.MailTestProperties;

/** Kinds and their defaults, preferences, the retry policy and the development-stand guard. */
class EmailChannelRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-16T08:00:00Z");

    @Test
    void defaultsFollowTheDesign() {
        assertThat(Arrays.stream(EmailKind.values()).filter(kind -> !kind.defaultOn()))
                .containsExactlyInAnyOrder(EmailKind.ORDER_TEACHER, EmailKind.ABSENCE_REPORTED,
                        EmailKind.TEACHER_UNREACHABLE);
        assertThat(Arrays.stream(EmailKind.values()).filter(kind -> !kind.switchable()))
                .containsExactlyInAnyOrder(EmailKind.SECURITY_TELEGRAM_RELINKED, EmailKind.TEST);
        assertThat(EmailKind.listedFor(EmailAudience.ADMIN)).containsExactly(EmailKind.GENERATION_FINISHED,
                EmailKind.SUBSTITUTION_DECLINED, EmailKind.SUBSTITUTION_OBJECTION, EmailKind.REQUEST_OVERDUE,
                EmailKind.ABSENCE_REPORTED, EmailKind.TEACHER_UNREACHABLE);
        assertThat(EmailKind.TEST.listed()).isFalse();
        assertThat(EmailKind.SUBSTITUTION_NOTICE.followsChange()).isTrue();
        assertThat(EmailKind.GENERATION_FINISHED.followsChange()).isFalse();
    }

    @Test
    void preferencesStoreOnlyDeviationsAndTheMasterSwitchWins() {
        EmailPreference preference = EmailPreference.defaults(UUID.randomUUID());
        assertThat(preference.allows(EmailKind.SUBSTITUTION_NOTICE)).isTrue();
        assertThat(preference.allows(EmailKind.ABSENCE_REPORTED)).isFalse();

        preference.update(true, Map.of(EmailKind.SUBSTITUTION_NOTICE, false, EmailKind.ABSENCE_REPORTED, true,
                EmailKind.GENERATION_FINISHED, true), NOW);
        assertThat(preference.getOverrides()).containsOnly(Map.entry("SUBSTITUTION_NOTICE", false),
                Map.entry("ABSENCE_REPORTED", true));
        assertThat(preference.allows(EmailKind.SUBSTITUTION_NOTICE)).isFalse();
        assertThat(preference.allows(EmailKind.ABSENCE_REPORTED)).isTrue();

        // back to the default removes the override
        preference.update(true, Map.of(EmailKind.SUBSTITUTION_NOTICE, true), NOW);
        assertThat(preference.getOverrides()).containsOnlyKeys("ABSENCE_REPORTED");

        preference.update(false, Map.of(), NOW);
        assertThat(preference.allows(EmailKind.GENERATION_FINISHED)).isFalse();
        assertThat(preference.allows(EmailKind.ABSENCE_REPORTED)).isFalse();
        // a security notice cannot be switched off
        assertThat(preference.allows(EmailKind.SECURITY_TELEGRAM_RELINKED)).isTrue();
    }

    @Test
    void retriesWithBackoffAndGivesUpOnPermanentFailures() {
        EmailRetryPolicy policy = new EmailRetryPolicy(MailTestProperties.of("max-attempts", "4"));
        assertThat(policy.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.backoff(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.backoff(10)).isEqualTo(Duration.ofMinutes(30));

        MailTransportException timeout = MailTransportException.transientFailure("SMTP недоступний");
        assertThat(policy.onSendFailure(timeout, 1, NOW)).isEqualTo(new Decision.Retry(NOW.plusSeconds(30)));
        assertThat(policy.onSendFailure(timeout, 4, NOW)).isInstanceOf(Decision.Fail.class);
        MailTransportException unknownUser = new MailTransportException("Сервер відхилив адресата: 550 User unknown",
                true, false, 550, null);
        assertThat(policy.onSendFailure(unknownUser, 1, NOW)).isInstanceOf(Decision.Fail.class);
        MailTransportException login = new MailTransportException("логін", false, true, 535, null);
        assertThat(policy.onSendFailure(login, 1, NOW)).isInstanceOf(Decision.Retry.class);
    }

    @Test
    void developmentStandSendsOnlyToALocalCatcher() {
        MailChannelProperties mailpit = MailTestProperties.of("host", "mailpit");
        MailChannelProperties gmail = MailTestProperties.of("host", "smtp.gmail.com");
        MailChannelProperties gmailOnPurpose = MailTestProperties.of("host", "smtp.gmail.com", "allow-real-smtp", "true");

        assertThat(MailSafetyGuard.check(MailTestProperties.of(), uri("http://localhost:8088")).mode())
                .isEqualTo(Mode.OFF);
        assertThat(MailSafetyGuard.check(mailpit, uri("http://localhost:8088")).mode()).isEqualTo(Mode.DEV_CATCHER);
        MailSafetyGuard.Verdict refused = MailSafetyGuard.check(gmail, uri("http://localhost:8088"));
        assertThat(refused.mode()).isEqualTo(Mode.OFF);
        assertThat(refused.problem()).contains("MAIL_ALLOW_REAL_SMTP");
        assertThat(MailSafetyGuard.check(gmailOnPurpose, uri("http://localhost:8088")).mode()).isEqualTo(Mode.SMTP);
        assertThat(MailSafetyGuard.check(gmail, uri("https://rozklad.vtc.vn.ua")).mode()).isEqualTo(Mode.SMTP);
        MailSafetyGuard.Verdict catcherInProduction = MailSafetyGuard.check(mailpit, uri("https://rozklad.vtc.vn.ua"));
        assertThat(catcherInProduction.mode()).isEqualTo(Mode.DEV_CATCHER);
        assertThat(catcherInProduction.problem()).contains("перехоплювач");
    }

    @Test
    void channelNeedsAUsableSender() {
        MailChannel channel = MailTestProperties.channel(MailTestProperties.of("host", "mailpit",
                "from", "Розклад ВТФК <rozklad@vtc.vn.ua>", "reply-to", "dispatcher@vtc.vn.ua"), "http://localhost:8088");
        assertThat(channel.enabled()).isTrue();
        assertThat(channel.from().getAddress()).isEqualTo("rozklad@vtc.vn.ua");
        assertThat(channel.from().getPersonal()).isEqualTo("Розклад ВТФК");
        assertThat(channel.replyTo().getAddress()).isEqualTo("dispatcher@vtc.vn.ua");
        assertThat(channel.messageIdDomain()).isEqualTo("vtc.vn.ua");

        assertThatThrownBy(() -> MailTestProperties.channel(MailTestProperties.of("host", "mailpit"),
                "http://localhost:8088")).hasMessageContaining("MAIL_FROM is required");
        assertThatThrownBy(() -> MailTestProperties.channel(MailTestProperties.of("host", "mailpit",
                "from", "a@b.ua, c@d.ua"), "http://localhost:8088")).hasMessageContaining("MAIL_FROM");
        // off: nothing is checked
        assertThat(MailTestProperties.channel(MailTestProperties.of(), "http://localhost:8088").enabled()).isFalse();
    }

    @Test
    void passwordNeverReachesToString() {
        assertThat(MailTestProperties.of("host", "smtp.gmail.com", "username", "rozklad@vtc.vn.ua",
                "password", "app-password-secret").toString())
                .doesNotContain("app-password-secret", "rozklad@vtc.vn.ua").contains("password=***");
    }

    private static java.net.URI uri(String value) {
        return java.net.URI.create(value);
    }
}
