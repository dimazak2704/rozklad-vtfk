package ua.vtc.rozklad.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ua.vtc.rozklad.notification.binding.BindingType;
import ua.vtc.rozklad.notification.binding.ChatBinding;
import ua.vtc.rozklad.notification.support.CoreFixtures;
import ua.vtc.rozklad.notification.support.FakeTelegramClient.Sent;
import ua.vtc.rozklad.notification.support.IntegrationTest;
import ua.vtc.rozklad.notification.telegram.TelegramApiFailure;

/** Outbound queue: per-chat order, retries with backoff and retry_after, permanent failures, log API. */
class DeliveryWorkerIT extends IntegrationTest {

    @Autowired
    private Outbox outbox;
    @Autowired
    private OutboundMessageRepository messages;

    @Test
    void messagesOfOneChatKeepTheirOrderWhileOtherChatsGoOn() {
        ChatBinding first = groupChatOf(-501, CoreFixtures.GROUP_4KI1, "4КІ1");
        ChatBinding second = groupChatOf(-502, CoreFixtures.GROUP_4KI2, "4КІ2");
        UUID one = queue(first, "1");
        queue(first, "2");
        queue(first, "3");
        queue(second, "інший чат");

        telegram.failNext(new TelegramApiFailure(502, "Bad Gateway", null, null, null));
        worker.drain();
        assertThat(messages.findById(one).orElseThrow().getStatus()).isEqualTo(OutboundStatus.RETRYING);
        assertThat(telegram.sentTo(-501)).isEmpty();
        assertThat(telegram.sentTo(-502)).extracting(Sent::text).containsExactly("інший чат");

        clock.advance(Duration.ofSeconds(3));
        worker.drain();
        assertThat(telegram.sentTo(-501)).extracting(Sent::text).containsExactly("1", "2", "3");
        assertThat(messages.findById(one).orElseThrow()).satisfies(message -> {
            assertThat(message.getStatus()).isEqualTo(OutboundStatus.SENT);
            assertThat(message.getAttempts()).isEqualTo(2);
            assertThat(message.getLastError()).isNull();
        });
    }

    @Test
    void tooManyRequestsWaitsForRetryAfterWithoutSpendingAttempts() {
        UUID id = queue(owner(), "hello");
        telegram.failNext(new TelegramApiFailure(429, "Too Many Requests: retry after 7", 7, null, null));
        worker.drain();
        assertThat(messages.findById(id).orElseThrow()).satisfies(message -> {
            assertThat(message.getStatus()).isEqualTo(OutboundStatus.RETRYING);
            assertThat(message.getAttempts()).isZero();
            assertThat(message.getNextAttemptAt()).isEqualTo(clock.instant().plusSeconds(7));
            assertThat(message.getLastError()).isEqualTo("Telegram 429: Too Many Requests: retry after 7");
        });

        clock.advance(Duration.ofSeconds(5));
        worker.drain();
        assertThat(telegram.sentTo(OWNER_CHAT)).isEmpty();
        clock.advance(Duration.ofSeconds(3));
        worker.drain();
        assertThat(telegram.sentTo(OWNER_CHAT)).hasSize(1);
        assertThat(messages.findById(id).orElseThrow().getAttempts()).isEqualTo(1);
    }

    @Test
    void serverErrorsBackOffExponentiallyAndFinallyFail() {
        UUID id = queue(owner(), "hello");
        for (int attempt = 1; attempt <= 8; attempt++) {
            telegram.failNext(new TelegramApiFailure(503, "Service Unavailable", null, null, null));
            worker.drain();
            clock.advance(Duration.ofMinutes(11));
        }
        assertThat(messages.findById(id).orElseThrow()).satisfies(message -> {
            assertThat(message.getStatus()).isEqualTo(OutboundStatus.FAILED);
            assertThat(message.getAttempts()).isEqualTo(8);
            assertThat(message.getLastError()).isEqualTo("Telegram 503: Service Unavailable");
        });
        assertThat(telegram.sentTo(OWNER_CHAT)).isEmpty();
    }

    @Test
    void backoffDoublesBetweenAttempts() {
        UUID id = queue(owner(), "hello");
        var start = clock.instant();
        telegram.failNext(TelegramApiFailure.network("SocketTimeoutException: timeout", null));
        worker.drain();
        assertThat(messages.findById(id).orElseThrow().getNextAttemptAt()).isEqualTo(start.plusSeconds(2));
        clock.advance(Duration.ofSeconds(2));
        telegram.failNext(TelegramApiFailure.network("SocketTimeoutException: timeout", null));
        worker.drain();
        assertThat(messages.findById(id).orElseThrow().getNextAttemptAt()).isEqualTo(start.plusSeconds(2 + 4));
        assertThat(messages.findById(id).orElseThrow().getLastError()).startsWith("Мережа: ");
    }

    @Test
    void blockedBotFailsAtOnce() {
        UUID id = queue(teacherChat(2001, CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В."), "hello");
        telegram.failNext(new TelegramApiFailure(403, "Forbidden: bot was blocked by the user", null, null, null));
        worker.drain();
        assertThat(messages.findById(id).orElseThrow()).satisfies(message -> {
            assertThat(message.getStatus()).isEqualTo(OutboundStatus.FAILED);
            assertThat(message.getAttempts()).isEqualTo(1);
        });
    }

    @Test
    void upgradedGroupGetsTheMessageUnderItsNewId() {
        ChatBinding group = groupChatOf(-555, CoreFixtures.GROUP_4KI1, "4КІ1");
        UUID id = queue(group, "hello");
        telegram.failNext(new TelegramApiFailure(400, "Bad Request: group chat was upgraded to a supergroup chat",
                null, -1009998887776L, null));
        worker.drain();
        assertThat(telegram.sentTo(-1009998887776L)).extracting(Sent::text).containsExactly("hello");
        assertThat(bindings.groupChat(-1009998887776L)).isPresent();
        assertThat(messages.findById(id).orElseThrow().getStatus()).isEqualTo(OutboundStatus.SENT);
    }

    @Test
    void unboundAndStaleMessagesAreNotSent() {
        ChatBinding group = groupChatOf(-601, CoreFixtures.GROUP_4KI1, "4КІ1");
        UUID unbound = queue(group, "hello");
        bindings.delete(group.getId());
        worker.drain();
        assertThat(messages.findById(unbound).orElseThrow()).satisfies(message -> {
            assertThat(message.getStatus()).isEqualTo(OutboundStatus.FAILED);
            assertThat(message.getLastError()).isEqualTo("Чат більше не прив’язаний до бота.");
        });

        UUID stale = queue(owner(), "old news");
        clock.advance(Duration.ofHours(25));
        worker.drain();
        assertThat(messages.findById(stale).orElseThrow()).satisfies(message -> {
            assertThat(message.getStatus()).isEqualTo(OutboundStatus.FAILED);
            assertThat(message.getLastError()).startsWith("Застаріле");
        });
        assertThat(telegram.messageCount()).isZero();
    }

    @Test
    void editThatChangesNothingCountsAsDelivered() {
        ChatBinding owner = owner();
        UUID id = outbox.enqueue(OutboundDraft.edit(Recipient.of(owner), DeliveryPurpose.SUBSTITUTION_STATUS, 77,
                "той самий текст", null)).getFirst().getId();
        telegram.failNext(new TelegramApiFailure(400, "Bad Request: message is not modified", null, null, null));
        worker.drain();
        assertThat(messages.findById(id).orElseThrow().getStatus()).isEqualTo(OutboundStatus.SENT);
    }

    @Test
    void longTextsAreSplitAndDuplicatesSkipped() {
        ChatBinding owner = owner();
        String longText = String.join("\n\n", java.util.Collections.nCopies(300, "Рядок розпорядження з деталями"));
        OutboundDraft draft = OutboundDraft.text(Recipient.of(owner), DeliveryPurpose.ORDER_ADMIN, longText)
                .dedupBy("test:long");
        assertThat(outbox.enqueue(draft)).hasSize(3);
        assertThat(outbox.enqueue(draft)).isEmpty();
        worker.drain();
        assertThat(telegram.sentTo(OWNER_CHAT)).hasSize(3).allMatch(sent -> sent.text().length() <= 4000);
    }

    @Test
    void deliveryLogIsForAdmins() throws Exception {
        UUID failed = queue(teacherChat(2001, CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В."), "x");
        telegram.failNext(new TelegramApiFailure(400, "Bad Request: chat not found", null, null, null));
        worker.drain();

        mockMvc.perform(get("/api/notify/deliveries").param("status", "FAILED").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(failed.toString()))
                .andExpect(jsonPath("$.items[0].chatId").value("•••2001"))
                .andExpect(jsonPath("$.items[0].chatTitle").value("Непийвода М.В."))
                .andExpect(jsonPath("$.items[0].bindingType").value("TEACHER"))
                .andExpect(jsonPath("$.items[0].attempts").value(1))
                .andExpect(jsonPath("$.items[0].error").value("Telegram 400: Bad Request: chat not found"));
        mockMvc.perform(get("/api/notify/deliveries").param("size", "500").with(admin()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/notify/deliveries").with(teacher("teacher-token"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/notify/deliveries")).andExpect(status().isUnauthorized());
    }

    @Test
    void testMessageGoesToTheOwnerRightAway() throws Exception {
        mockMvc.perform(post("/api/notify/test").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.purpose").value("TEST"))
                .andExpect(jsonPath("$.bindingType").value("ADMIN_CHAT"));
        assertThat(telegram.sentTo(OWNER_CHAT)).extracting(Sent::text)
                .containsExactly("Тест: бот Розклад ВТФК підключено ✅");

        telegram.failNext(new TelegramApiFailure(400, "Bad Request: chat not found", null, null, null));
        mockMvc.perform(post("/api/notify/test").with(admin()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value(
                        "Telegram не прийняв тестове повідомлення: Telegram 400: Bad Request: chat not found"));
        mockMvc.perform(post("/api/notify/test").with(teacher("teacher-token"))).andExpect(status().isForbidden());
    }

    @Test
    void allPushesLandInTheLogWithTheirBindingSnapshot() {
        ChatBinding group = groupChatOf(-701, CoreFixtures.GROUP_4KI1, "4КІ1");
        queue(group, "x");
        worker.drain();
        assertThat(messages.findAll()).singleElement().satisfies(message -> {
            assertThat(message.getBindingType()).isEqualTo(BindingType.GROUP_CHAT);
            assertThat(message.getChatTitle()).isEqualTo("Чат 4КІ1 → 4КІ1");
            assertThat(message.getTelegramMessageId()).isNotNull();
            assertThat(message.getSentAt()).isEqualTo(clock.instant());
        });
    }

    private UUID queue(ChatBinding binding, String text) {
        List<OutboundMessage> queued = outbox.enqueue(OutboundDraft.text(Recipient.of(binding),
                DeliveryPurpose.ORDER_GROUP, text));
        return queued.getFirst().getId();
    }
}
