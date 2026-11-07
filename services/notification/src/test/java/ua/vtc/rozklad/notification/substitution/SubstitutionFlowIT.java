package ua.vtc.rozklad.notification.substitution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.contracts.events.SubstitutionConfirmationRequested;
import ua.vtc.rozklad.contracts.events.SubstitutionStatusChanged;
import ua.vtc.rozklad.notification.core.UpstreamRejectedException;
import ua.vtc.rozklad.notification.core.UpstreamUnavailableException;
import ua.vtc.rozklad.notification.events.EventHandlers;
import ua.vtc.rozklad.notification.support.CoreFixtures;
import ua.vtc.rozklad.notification.support.FakeCoreClient.Reply;
import ua.vtc.rozklad.notification.support.FakeTelegramClient.Edit;
import ua.vtc.rozklad.notification.support.FakeTelegramClient.Sent;
import ua.vtc.rozklad.notification.support.IntegrationTest;
import ua.vtc.rozklad.notification.support.Order35;

/** substitution.* events → request with buttons → teacher's answer → final state, reminders, admin notices. */
class SubstitutionFlowIT extends IntegrationTest {

    private static final long TEACHER = 2001;
    private static final UUID CHANGE = Order35.CHANGE_4KI1;

    @Autowired
    private EventHandlers handlers;
    @Autowired
    private SubstitutionRequests substitutions;

    @BeforeEach
    void bindings() {
        owner();
        teacherChat(TEACHER, CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В.");
    }

    @Test
    void teacherConfirmsWithOneTapAndTheMessageShowsTheFinalState() {
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();

        Sent request = telegram.lastSentTo(TEACHER);
        assertThat(request.text()).startsWith("🙋 <b>Прохання про заміну</b>")
                .contains("🕘 2 пара · 09:40–10:40", "👥 4КІ1", "🚪 Аудиторія 6", "👤 Замість: Кренцін М.Д.")
                .endsWith("Будь ласка, дайте відповідь до 19.10 о 10:00.");
        assertThat(request.buttons()).containsExactly("✅ Підтверджую", "❌ Не можу");

        press(TEACHER, request.messageId(), request.callbackOf("✅ Підтверджую").orElseThrow());
        assertThat(core.replies()).containsExactly(new Reply(CHANGE, CoreFixtures.TEACHER_NEPYIVODA,
                ConfirmationDecision.CONFIRM));
        Edit done = telegram.lastEditIn(TEACHER);
        assertThat(done.messageId()).isEqualTo(request.messageId());
        assertThat(done.text()).endsWith("✅ <b>Ви підтвердили заміну.</b> Дякуємо!");
        assertThat(done.keyboard()).isNull();
        assertThat(telegram.lastCallbackAnswer().text()).isEqualTo("Дякуємо! Заміну підтверджено ✅");
        assertThat(substitutions.find(CHANGE)).get().extracting(SubstitutionRequest::getStatus)
                .isEqualTo(ChangeStatus.CONFIRMED);

        // подія від core про той самий статус нічого вже не змінює
        handlers.handle(statusChanged(ChangeStatus.CONFIRMED));
        worker.drain();
        assertThat(telegram.editsIn(TEACHER)).hasSize(1);
        assertThat(telegram.sentTo(OWNER_CHAT)).isEmpty();
    }

    @Test
    void refusalIsEditedAndReportedToAdmins() {
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();
        Sent request = telegram.lastSentTo(TEACHER);

        press(TEACHER, request.messageId(), request.callbackOf("❌ Не можу").orElseThrow());
        assertThat(telegram.lastEditIn(TEACHER).text()).endsWith("Диспетчер підбере іншого викладача.");

        handlers.handle(statusChanged(ChangeStatus.DECLINED));
        worker.drain();
        assertThat(telegram.lastSentTo(OWNER_CHAT).text()).isEqualTo("❌ <b>Відмова від заміни</b>\n"
                + "Непийвода М.В. не може провести пару: понеділок, 19 жовтня · 2 пара · 4КІ1 · Надійність, "
                + "діагностика та експлуатація комп'ютерних систем і мереж\nПідберіть іншого викладача.");
    }

    @Test
    void cancellationByTheDispatcherEditsTheRequestAndTheReminder() {
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();
        assertThat(telegram.sentTo(TEACHER)).extracting(Sent::text)
                .satisfiesExactly(first -> assertThat(first).startsWith("🙋"),
                        second -> assertThat(second).startsWith("⏰ <b>Нагадування: потрібна ваша відповідь</b>"));

        handlers.handle(statusChanged(ChangeStatus.CANCELLED));
        worker.drain();
        assertThat(telegram.editsIn(TEACHER)).hasSize(2)
                .allSatisfy(edit -> assertThat(edit.text()).endsWith("🚫 <b>Заміну скасовано.</b> Нічого робити не потрібно."));
        assertThat(telegram.editsIn(TEACHER)).extracting(Edit::messageId)
                .containsExactlyInAnyOrderElementsOf(telegram.sentTo(TEACHER).stream().map(Sent::messageId).toList());
    }

    @Test
    void teacherWithoutTelegramIsReportedToAdmins() {
        handlers.handle(requested(CoreFixtures.TEACHER_KRENTSIN));
        worker.drain();
        assertThat(telegram.lastSentTo(OWNER_CHAT).text()).startsWith("⚠️ <b>Кренцін М.Д. не підключив Telegram</b>")
                .contains("Запит на заміну не надіслано");
        assertThat(telegram.sentTo(TEACHER)).isEmpty();
    }

    @Test
    void outdatedForeignAndUnreachableAnswers() {
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();
        Sent request = telegram.lastSentTo(TEACHER);

        // інший викладач не може відповісти за колегу
        teacherChat(2002, CoreFixtures.TEACHER_KRENTSIN, "Кренцін М.Д.");
        press(2002, request.messageId(), "sr:c:" + CHANGE);
        assertThat(telegram.lastCallbackAnswer().text()).isEqualTo("Цей запит адресовано іншому викладачу.");

        core.failReplies(new UpstreamUnavailableException("core", "down", null));
        press(TEACHER, request.messageId(), "sr:c:" + CHANGE);
        assertThat(telegram.lastCallbackAnswer().alert()).isTrue();
        assertThat(telegram.editsIn(TEACHER)).isEmpty();

        core.failReplies(new UpstreamRejectedException(409, "Заміна не очікує підтвердження."));
        press(TEACHER, request.messageId(), "sr:c:" + CHANGE);
        assertThat(telegram.lastEditIn(TEACHER).text()).endsWith("ℹ️ <b>Цей запит уже неактуальний.</b>");
        assertThat(telegram.lastCallbackAnswer().text()).isEqualTo("Цей запит уже неактуальний");
    }

    @Test
    void deadlineBringsOneReminderAndOneAdminNotice() {
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();
        assertThat(substitutions.remindOverdue()).isZero();

        clock.set(Instant.parse("2026-10-19T07:01:00Z"));
        assertThat(substitutions.remindOverdue()).isEqualTo(1);
        assertThat(substitutions.remindOverdue()).isZero();
        worker.drain();

        assertThat(telegram.lastSentTo(TEACHER).text()).startsWith("⏰ <b>Нагадування");
        assertThat(telegram.lastSentTo(TEACHER).buttons()).containsExactly("✅ Підтверджую", "❌ Не можу");
        assertThat(telegram.lastSentTo(OWNER_CHAT).text()).startsWith("⏰ <b>Немає відповіді щодо заміни</b>")
                .contains("Непийвода М.В. не відповів до 19.10 о 10:00");
    }

    @Test
    void reassignedChangeRetiresThePreviousTeachersMessage() {
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();
        Sent first = telegram.lastSentTo(TEACHER);
        teacherChat(2002, CoreFixtures.TEACHER_KRENTSIN, "Кренцін М.Д.");

        handlers.handle(requested(CoreFixtures.TEACHER_KRENTSIN));
        worker.drain();
        assertThat(telegram.lastEditIn(TEACHER).messageId()).isEqualTo(first.messageId());
        assertThat(telegram.lastEditIn(TEACHER).text()).endsWith("Цей запит уже неактуальний.</b>");
        assertThat(telegram.lastSentTo(2002).text()).startsWith("🙋 <b>Прохання про заміну</b>");
    }

    private static SubstitutionConfirmationRequested requested(UUID teacherId) {
        return new SubstitutionConfirmationRequested(EventEnvelope.create(java.time.Clock.systemUTC()), CHANGE,
                teacherId, Order35.DATE, 2, LocalTime.of(9, 40), LocalTime.of(10, 40), "4КІ1", 0,
                "Надійність, діагностика та експлуатація комп'ютерних систем і мереж", "Кренцін М.Д.", "6",
                Instant.parse("2026-10-19T07:00:00Z"));
    }

    private static SubstitutionStatusChanged statusChanged(ChangeStatus status) {
        return new SubstitutionStatusChanged(EventEnvelope.create(java.time.Clock.systemUTC()), CHANGE, status,
                CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE, "4КІ1");
    }
}
