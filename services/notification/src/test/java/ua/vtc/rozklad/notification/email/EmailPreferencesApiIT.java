package ua.vtc.rozklad.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import ua.vtc.rozklad.notification.config.TokenRoles;
import ua.vtc.rozklad.notification.core.CoreDtos.MailRecipient;
import ua.vtc.rozklad.notification.support.CoreFixtures;
import ua.vtc.rozklad.notification.support.IntegrationTest;

/** {@code /api/notify/email/**}: own settings from the token, validation, the test letter, admin status and log. */
class EmailPreferencesApiIT extends IntegrationTest {

    private static final UUID TEACHER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ADMIN = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID INVITED = UUID.fromString("77777777-7777-4777-8777-777777777777");
    private static final UUID PRIVATE = UUID.fromString("88888888-8888-4888-8888-888888888888");

    @Autowired
    private EmailPreferences preferences;

    @BeforeEach
    void accounts() {
        core.addMailRecipient(new MailRecipient(TEACHER, CoreFixtures.TEACHER_NEPYIVODA, "Непийвода Марія",
                "nepyivoda", "nepyivoda@vtc.vn.ua", false, List.of("TEACHER"), true, false, false));
        core.addMailRecipient(new MailRecipient(ADMIN, null, "Диспетчер", "admin", "dispatcher@gmail.com", true,
                List.of("ADMIN"), true, false, false));
        core.addMailRecipient(new MailRecipient(INVITED, CoreFixtures.TEACHER_KRENTSIN, "Кренцін", "krentsin", null,
                false, List.of("TEACHER"), true, false, false));
        core.addMailRecipient(new MailRecipient(PRIVATE, UUID.randomUUID(), "Приватна", "private",
                "someone@gmail.com", false, List.of("TEACHER"), true, false, false));
    }

    @Test
    void aTeacherSeesHisOwnAddressAndOnlyTeacherKinds() throws Exception {
        mockMvc.perform(get("/api/notify/email/me").with(as(TEACHER, "TEACHER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel.enabled").value(true))
                .andExpect(jsonPath("$.address").value("nepyivoda@vtc.vn.ua"))
                .andExpect(jsonPath("$.addressStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.verifiedBy").value("TRUSTED_DOMAIN"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.version").value(nullValue()))
                .andExpect(jsonPath("$.kinds[*].kind").value(containsInAnyOrder("SUBSTITUTION_NOTICE",
                        "SUBSTITUTION_REQUEST", "SUBSTITUTION_CHANGED", "SUBSTITUTION_CANCELLED",
                        "SUBSTITUTION_REMINDER", "ORDER_TEACHER", "SECURITY_TELEGRAM_RELINKED")))
                .andExpect(jsonPath("$.kinds[?(@.kind == 'ORDER_TEACHER')].enabled").value(hasItem(false)))
                .andExpect(jsonPath("$.kinds[?(@.kind == 'SECURITY_TELEGRAM_RELINKED')].switchable")
                        .value(hasItem(false)));
    }

    @Test
    void aDispatcherSeesDispatcherKindsAndAGoogleVerifiedAddress() throws Exception {
        mockMvc.perform(get("/api/notify/email/me").with(as(ADMIN, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addressStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.verifiedBy").value("GOOGLE"))
                .andExpect(jsonPath("$.kinds[*].kind").value(containsInAnyOrder("GENERATION_FINISHED",
                        "SUBSTITUTION_DECLINED", "SUBSTITUTION_OBJECTION", "REQUEST_OVERDUE", "ABSENCE_REPORTED",
                        "TEACHER_UNREACHABLE")));
    }

    @Test
    void missingAndUnverifiedAddressesAreExplained() throws Exception {
        mockMvc.perform(get("/api/notify/email/me").with(as(INVITED, "TEACHER")))
                .andExpect(jsonPath("$.addressStatus").value("MISSING"))
                .andExpect(jsonPath("$.address").value(nullValue()));
        mockMvc.perform(get("/api/notify/email/me").with(as(PRIVATE, "TEACHER")))
                .andExpect(jsonPath("$.addressStatus").value("UNVERIFIED"));

        mockMvc.perform(post("/api/notify/email/me/test").with(as(INVITED, "TEACHER")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("email-missing"));
        mockMvc.perform(post("/api/notify/email/me/test").with(as(PRIVATE, "TEACHER")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("email-not-verified"));
    }

    @Test
    void savesOnlyTheCallersOwnSettings() throws Exception {
        mockMvc.perform(put("/api/notify/email/me").with(as(TEACHER, "TEACHER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"kinds\":{\"SUBSTITUTION_NOTICE\":false,\"ORDER_TEACHER\":true}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.kinds[?(@.kind == 'SUBSTITUTION_NOTICE')].enabled").value(hasItem(false)))
                .andExpect(jsonPath("$.kinds[?(@.kind == 'ORDER_TEACHER')].enabled").value(hasItem(true)));

        mockMvc.perform(get("/api/notify/email/me").with(as(TEACHER, "TEACHER")))
                .andExpect(jsonPath("$.kinds[?(@.kind == 'SUBSTITUTION_NOTICE')].enabled").value(hasItem(false)))
                .andExpect(jsonPath("$.kinds[?(@.kind == 'SUBSTITUTION_REQUEST')].enabled").value(hasItem(true)));
        // the dispatcher's own settings are untouched
        mockMvc.perform(get("/api/notify/email/me").with(as(ADMIN, "ADMIN")))
                .andExpect(jsonPath("$.version").value(nullValue()));

        // an outdated version
        mockMvc.perform(put("/api/notify/email/me").with(as(TEACHER, "TEACHER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false,\"version\":7}"))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/api/notify/email/me").with(as(TEACHER, "TEACHER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false,\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void theChoiceOfKindsSurvivesSwitchingLettersOffAndOn() throws Exception {
        mockMvc.perform(put("/api/notify/email/me").with(as(TEACHER, "TEACHER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"kinds\":{\"SUBSTITUTION_NOTICE\":false,\"ORDER_TEACHER\":true}}"))
                .andExpect(status().isOk());

        // letters off: each kind still shows the person's choice (the page sends it back as it is)
        mockMvc.perform(put("/api/notify/email/me").with(as(TEACHER, "TEACHER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.kinds[?(@.kind == 'ORDER_TEACHER')].enabled").value(hasItem(true)))
                .andExpect(jsonPath("$.kinds[?(@.kind == 'SUBSTITUTION_NOTICE')].enabled").value(hasItem(false)))
                .andExpect(jsonPath("$.kinds[?(@.kind == 'SUBSTITUTION_REQUEST')].enabled").value(hasItem(true)));

        mockMvc.perform(put("/api/notify/email/me").with(as(TEACHER, "TEACHER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"kinds\":{\"SUBSTITUTION_NOTICE\":false,\"ORDER_TEACHER\":true,"
                                + "\"SUBSTITUTION_REQUEST\":true}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kinds[?(@.kind == 'ORDER_TEACHER')].enabled").value(hasItem(true)))
                .andExpect(jsonPath("$.kinds[?(@.kind == 'SUBSTITUTION_REQUEST')].enabled").value(hasItem(true)));
        // while letters were off nothing went out, the choice is what it was
        EmailPreference saved = preferences.of(TEACHER);
        assertThat(saved.allows(EmailKind.ORDER_TEACHER)).isTrue();
        assertThat(saved.allows(EmailKind.SUBSTITUTION_NOTICE)).isFalse();
    }

    @Test
    void rejectsUnknownForeignAndFixedKinds() throws Exception {
        mockMvc.perform(put("/api/notify/email/me").with(as(TEACHER, "TEACHER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"kinds\":{\"NOPE\":true}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("kinds.NOPE"));
        mockMvc.perform(put("/api/notify/email/me").with(as(TEACHER, "TEACHER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"kinds\":{\"GENERATION_FINISHED\":true}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("email-kind-not-allowed"));
        mockMvc.perform(put("/api/notify/email/me").with(as(TEACHER, "TEACHER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"kinds\":{\"SECURITY_TELEGRAM_RELINKED\":false}}"))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(put("/api/notify/email/me").with(as(TEACHER, "TEACHER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kinds\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("enabled"));
    }

    @Test
    void theTestLetterGoesToTheCallersOwnAddressThreeTimesAnHour() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/notify/email/me/test").with(as(TEACHER, "TEACHER")))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("QUEUED"));
        }
        mockMvc.perform(post("/api/notify/email/me/test").with(as(TEACHER, "TEACHER")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("email-test-limit"));

        mailWorker.drain();
        assertThat(mail.sentTo("nepyivoda@vtc.vn.ua")).hasSize(3)
                .allSatisfy(letter -> assertThat(letter.subject()).isEqualTo("Тестовий лист від «Розклад ВТФК»"));
        assertThat(mail.sent()).hasSize(3);

        clock.advance(Duration.ofHours(1));
        mockMvc.perform(post("/api/notify/email/me/test").with(as(TEACHER, "TEACHER")))
                .andExpect(status().isAccepted());
    }

    @Test
    void aDisabledRevokedOrDemoAccountGetsNoTestLetter() throws Exception {
        UUID demo = UUID.fromString("99999999-9999-4999-8999-999999999999");
        core.addMailRecipient(new MailRecipient(demo, null, "Демо", "demo", "demo@vtc.vn.ua", true,
                List.of("ADMIN"), true, false, true));

        // the worker would drop the letter of an inactive account: say so instead of «queued»
        mockMvc.perform(post("/api/notify/email/me/test").with(as(demo, "ADMIN")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("email-account-inactive"));
        mailWorker.drain();
        assertThat(mail.sent()).isEmpty();
    }

    @Test
    void coreDownIsA503() throws Exception {
        core.setMailRecipientsUnavailable(true);
        mockMvc.perform(get("/api/notify/email/me").with(as(TEACHER, "TEACHER")))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void theDispatcherSeesTheServerStatusWithoutCredentialsAndAMaskedLog() throws Exception {
        mockMvc.perform(get("/api/notify/email/status").with(as(ADMIN, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.mode").value("DEV_CATCHER"))
                .andExpect(jsonPath("$.host").value("localhost"))
                .andExpect(jsonPath("$.from").value("Розклад ВТФК <rozklad@rozklad.local>"))
                .andExpect(jsonPath("$.tls").value("STARTTLS"))
                .andExpect(content().string(not(containsString("password"))));

        mockMvc.perform(post("/api/notify/email/me/test").with(as(TEACHER, "TEACHER")))
                .andExpect(status().isAccepted());
        mailWorker.drain();
        mockMvc.perform(get("/api/notify/email/deliveries?status=SENT&kind=TEST").with(as(ADMIN, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].to").value("n•••@vtc.vn.ua"))
                .andExpect(jsonPath("$.items[0].accountName").value("Непийвода Марія"))
                .andExpect(jsonPath("$.items[0].subject").value("Тестовий лист від «Розклад ВТФК»"))
                .andExpect(jsonPath("$.items[0].status").value("SENT"))
                .andExpect(content().string(not(containsString("nepyivoda@"))));
        mockMvc.perform(get("/api/notify/email/deliveries?status=NOPE").with(as(ADMIN, "ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyDispatchersSeeTheStatusAndTheLog() throws Exception {
        mockMvc.perform(get("/api/notify/email/status").with(as(TEACHER, "TEACHER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/notify/email/deliveries").with(as(TEACHER, "TEACHER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/notify/email/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/notify/email/me").with(as(UUID.randomUUID(), "SERVICE")))
                .andExpect(status().isForbidden());
    }

    private static JwtRequestPostProcessor as(UUID account, String role) {
        return jwt().jwt(token -> token.subject(account.toString())
                        .tokenValue("token-" + account)
                        .claim("roles", List.of(role))
                        .claim("preferred_username", "user"))
                .authorities(TokenRoles::authorities);
    }
}
