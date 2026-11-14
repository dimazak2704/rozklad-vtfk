package ua.vtc.rozklad.notification.substitution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.contracts.SubstitutionRequestMode;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.contracts.events.SubstitutionConfirmationRequested;
import ua.vtc.rozklad.contracts.events.SubstitutionDetailsChanged;
import ua.vtc.rozklad.contracts.events.SubstitutionStatusChanged;
import ua.vtc.rozklad.contracts.events.SubstitutionTeacherReplied;
import ua.vtc.rozklad.contracts.events.RozkladEvent;
import ua.vtc.rozklad.notification.core.UpstreamRejectedException;
import ua.vtc.rozklad.notification.core.UpstreamUnavailableException;
import ua.vtc.rozklad.notification.delivery.DeliveryPurpose;
import ua.vtc.rozklad.notification.delivery.OutboundMessageSent;
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
    private static final UUID CHANGE_2 = UUID.fromString("6c1f0a52-3d4e-4f60-9a7b-8c9d0e1f2a01");
    private static final UUID CHANGE_3 = UUID.fromString("6c1f0a52-3d4e-4f60-9a7b-8c9d0e1f2a02");
    private static final UUID CHANGE_4 = UUID.fromString("6c1f0a52-3d4e-4f60-9a7b-8c9d0e1f2a03");
    /** {@code app.bot.no-telegram-notice-delay} default. */
    private static final Duration NOTICE_DELAY = Duration.ofMinutes(2);

    @Autowired
    private EventHandlers handlers;
    @Autowired
    private SubstitutionRequests substitutions;
    @Autowired
    private SubstitutionRequestRepository requestRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

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
        assertThat(telegram.lastEditIn(TEACHER).text()).endsWith("Диспетчер обере інший варіант.");

        handlers.handle(statusChanged(ChangeStatus.DECLINED));
        worker.drain();
        assertThat(telegram.lastSentTo(OWNER_CHAT).text()).isEqualTo("❌ <b>Відмова від заміни</b>\n"
                + "Непийвода М.В. не може взяти 2 пару 4КІ1 у понеділок, 19 жовтня. "
                + "Оберіть інший варіант на сторінці змін.");
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
    void teacherWithoutTelegramIsReportedToAdminsWhenTheRequestStaysUnanswered() {
        handlers.handle(requested(CoreFixtures.TEACHER_KRENTSIN));
        worker.drain();
        assertThat(telegram.sentTo(OWNER_CHAT)).as("not right away").isEmpty();
        assertThat(substitutions.reportTeachersWithoutTelegram()).isZero();

        clock.advance(NOTICE_DELAY);
        assertThat(substitutions.reportTeachersWithoutTelegram()).isEqualTo(1);
        worker.drain();
        assertThat(telegram.lastSentTo(OWNER_CHAT).text()).startsWith("⚠️ <b>Кренцін М.Д. не підключив Telegram</b>")
                .contains("Запит на заміну не надіслано: понеділок, 19 жовтня · 2 пара · 4КІ1")
                .contains("Підтвердьте заміну в панелі");
        assertThat(telegram.sentTo(TEACHER)).isEmpty();
    }

    @Test
    void oneWarningPerTeacherAndDateNoMatterHowManyChangesAndReminders() {
        handlers.handle(requested(CHANGE, CoreFixtures.TEACHER_KRENTSIN, Order35.DATE, 2));
        handlers.handle(requested(CHANGE_2, CoreFixtures.TEACHER_KRENTSIN, Order35.DATE, 3));
        clock.advance(NOTICE_DELAY);
        assertThat(substitutions.reportTeachersWithoutTelegram()).isEqualTo(1);
        worker.drain();
        assertThat(telegram.sentTo(OWNER_CHAT)).singleElement().extracting(Sent::text).asString()
                .startsWith("⚠️ <b>Кренцін М.Д. не підключив Telegram</b>\n"
                        + "Запити на заміну не надіслано, понеділок, 19 жовтня:\n"
                        + "• 2 пара · 4КІ1 · Надійність, діагностика та експлуатація комп'ютерних систем і мереж\n"
                        + "• 3 пара · 4КІ1 · Надійність, діагностика та експлуатація комп'ютерних систем і мереж\n"
                        + "Підтвердьте заміни в панелі або зв’яжіться з викладачем.");

        // нагадування core про ту саму зміну і ще одна зміна того ж викладача на ту саму дату — тихо
        handlers.handle(requested(CHANGE, CoreFixtures.TEACHER_KRENTSIN, Order35.DATE, 2));
        handlers.handle(requested(CHANGE_3, CoreFixtures.TEACHER_KRENTSIN, Order35.DATE, 4));
        clock.advance(NOTICE_DELAY.multipliedBy(3));
        assertThat(substitutions.reportTeachersWithoutTelegram()).isZero();
        // минув дедлайн: викладачу нічого не надсилали, адміни вже попереджені — без «немає відповіді»
        clock.set(Instant.parse("2026-10-19T07:01:00Z"));
        assertThat(substitutions.remindOverdue()).isEqualTo(3);
        worker.drain();
        assertThat(telegram.sentTo(OWNER_CHAT)).hasSize(1);
        assertThat(telegram.sentTo(TEACHER)).isEmpty();

        // інша дата — окреме попередження
        handlers.handle(requested(CHANGE_4, CoreFixtures.TEACHER_KRENTSIN, Order35.DATE.plusDays(1), 2));
        clock.advance(NOTICE_DELAY);
        assertThat(substitutions.reportTeachersWithoutTelegram()).isEqualTo(1);
        worker.drain();
        assertThat(telegram.lastSentTo(OWNER_CHAT).text()).contains("вівторок, 20 жовтня · 2 пара");
    }

    @Test
    void seededOrQuicklyConfirmedChangesAndPastLessonsCauseNoWarning() {
        // засів опублікованого розпорядження: запит, одразу підтвердження/публікація
        handlers.handle(requested(CHANGE, CoreFixtures.TEACHER_KRENTSIN, Order35.DATE, 2));
        handlers.handle(requested(CHANGE_2, CoreFixtures.TEACHER_KRENTSIN, Order35.DATE, 3));
        handlers.handle(statusChanged(CHANGE, ChangeStatus.CONFIRMED, CoreFixtures.TEACHER_KRENTSIN));
        handlers.handle(statusChanged(CHANGE_2, ChangeStatus.APPLIED, CoreFixtures.TEACHER_KRENTSIN));
        // минулий день: попереджати вже нема про що
        handlers.handle(requested(CHANGE_3, CoreFixtures.TEACHER_KRENTSIN, Order35.DATE.minusDays(3), 2));

        clock.advance(NOTICE_DELAY.multipliedBy(5));
        assertThat(substitutions.reportTeachersWithoutTelegram()).isZero();
        assertThat(substitutions.reportTeachersWithoutTelegram()).isZero();
        worker.drain();
        assertThat(telegram.sentTo(OWNER_CHAT)).isEmpty();
    }

    /**
     * A request of a teacher without Telegram can be overdue and unreported at once, so both every-minute
     * jobs lock it. Their locking queries take the rows in one order (change id), whatever the deadlines,
     * teachers and pairs say — two jobs or two instances cannot deadlock on opposite orders. The warning
     * still lists the pairs in order.
     */
    @Test
    void scheduledJobsLockSharedRowsInTheSameOrder() {
        // порядок change id (…a01 < …a02 < …a03) протилежний порядку дедлайнів і пар
        Instant late = Instant.parse("2026-10-19T07:00:00Z");
        handlers.handle(requested(CHANGE_2, CoreFixtures.TEACHER_KRENTSIN, Order35.DATE, 4, late));
        handlers.handle(requested(CHANGE_3, CoreFixtures.TEACHER_KRENTSIN, Order35.DATE, 3, late.minusSeconds(60)));
        handlers.handle(requested(CHANGE_4, CoreFixtures.TEACHER_KRENTSIN, Order35.DATE, 2, late.minusSeconds(120)));
        clock.set(late.plusSeconds(60));
        Instant now = clock.instant();

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(tx -> {
            assertThat(requestRepository.findOverdue(now)).extracting(SubstitutionRequest::getChangeId)
                    .containsExactly(CHANGE_2, CHANGE_3, CHANGE_4);
            assertThat(requestRepository.findUnreportedWithoutTelegram(now)).extracting(SubstitutionRequest::getChangeId)
                    .containsExactly(CHANGE_2, CHANGE_3, CHANGE_4);
            tx.setRollbackOnly();
        });

        assertThat(substitutions.reportTeachersWithoutTelegram()).isEqualTo(1);
        worker.drain();
        assertThat(telegram.lastSentTo(OWNER_CHAT).text())
                .containsSubsequence("• 2 пара", "• 3 пара", "• 4 пара");
    }

    /** Teacher names come from core over HTTP; the jobs look them up before locking, never while holding rows. */
    @Test
    void scheduledJobsDoNotCallCoreWhileHoldingTheRows() {
        handlers.handle(requested(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE, 2));
        handlers.handle(requested(CHANGE_2, CoreFixtures.TEACHER_KRENTSIN, Order35.DATE, 3));
        worker.drain();
        flushCaches();
        core.reset();
        clock.set(Instant.parse("2026-10-19T07:01:00Z"));

        assertThat(substitutions.reportTeachersWithoutTelegram()).isEqualTo(1);
        assertThat(substitutions.remindOverdue()).isEqualTo(2);
        worker.drain();

        assertThat(core.teacherCalls()).isPositive();
        assertThat(core.teacherCallsInTransaction()).isZero();
        assertThat(telegram.sentTo(OWNER_CHAT)).extracting(Sent::text)
                .anySatisfy(text -> assertThat(text).startsWith("⚠️ <b>Кренцін М.Д. не підключив Telegram</b>"))
                .anySatisfy(text -> assertThat(text).contains("Непийвода М.В. не відповів до 19.10 о 10:00"));
        // нічого не чекає — нічого не блокуємо і в core не ходимо
        core.reset();
        flushCaches();
        assertThat(substitutions.remindOverdue()).isZero();
        assertThat(substitutions.reportTeachersWithoutTelegram()).isZero();
        assertThat(core.teacherCalls()).isZero();
    }

    @Test
    void confirmationRacingWithCoresStatusEventNeverShowsAnError() throws Exception {
        raceButtonWithStatusEvent("✅ Підтверджую", ChangeStatus.CONFIRMED);
        assertThat(telegram.lastCallbackAnswer().text()).isEqualTo("Дякуємо! Заміну підтверджено ✅");
        assertThat(telegram.editsIn(TEACHER))
                .allSatisfy(edit -> assertThat(edit.text()).endsWith("✅ <b>Ви підтвердили заміну.</b> Дякуємо!"));
    }

    @Test
    void refusalRacingWithCoresStatusEventNeverShowsAnError() throws Exception {
        raceButtonWithStatusEvent("❌ Не можу", ChangeStatus.DECLINED);
        assertThat(telegram.lastCallbackAnswer().text()).isEqualTo("Відмову передано диспетчеру");
        assertThat(telegram.editsIn(TEACHER))
                .allSatisfy(edit -> assertThat(edit.text()).endsWith("Диспетчер обере інший варіант."));
        assertThat(telegram.sentTo(OWNER_CHAT)).singleElement().extracting(Sent::text).asString()
                .startsWith("❌ <b>Відмова від заміни</b>");
    }

    @Test
    void secondTapOnTheSameAnswerRepeatsTheResult() {
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();
        Sent request = telegram.lastSentTo(TEACHER);
        press(TEACHER, request.messageId(), request.callbackOf("✅ Підтверджую").orElseThrow());

        core.failReplies(new UpstreamRejectedException(409, "Заміна не очікує підтвердження."));
        press(TEACHER, request.messageId(), request.callbackOf("✅ Підтверджую").orElseThrow());
        assertThat(telegram.lastEditIn(TEACHER).text()).endsWith("✅ <b>Ви підтвердили заміну.</b> Дякуємо!");
        assertThat(telegram.lastCallbackAnswer().text()).isEqualTo("Дякуємо! Заміну підтверджено ✅");
        assertThat(telegram.lastCallbackAnswer().alert()).isFalse();

        // інша відповідь на вже підтверджену заміну — запит неактуальний, а повідомлення лишає підсумок
        press(TEACHER, request.messageId(), request.callbackOf("❌ Не можу").orElseThrow());
        assertThat(telegram.lastCallbackAnswer().text()).isEqualTo("Цей запит уже неактуальний");
        assertThat(telegram.lastEditIn(TEACHER).text()).endsWith("✅ <b>Ви підтвердили заміну.</b> Дякуємо!");
    }

    /**
     * The production race of 08.11.2026: core's {@code substitution.status-changed} for the very answer
     * is being processed (its UPDATE of the request row is flushed, not committed) while the teacher's
     * button handler records the same answer. Before the fix the handler's version-checked UPDATE found
     * 0 rows once the event committed and the teacher saw «Щось пішло не так» although core had the answer.
     */
    private void raceButtonWithStatusEvent(String button, ChangeStatus status) throws Exception {
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();
        Sent request = telegram.lastSentTo(TEACHER);

        CountDownLatch eventFlushed = new CountDownLatch(1);
        CountDownLatch buttonPressed = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CompletableFuture<Void> event = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(tx -> {
            handlers.handle(statusChanged(status));
            requestRepository.flush();
            eventFlushed.countDown();
            try {
                assertThat(buttonPressed.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // подію комітимо лише тоді, коли транзакція кнопки справді чекає на блокування цього рядка:
            // так тест щоразу перевіряє саме небезпечний порядок, незалежно від швидкості машини
            await(Duration.ofSeconds(10), this::anotherTransactionWaitsForThisOne);
        }));
        assertThat(eventFlushed.await(10, TimeUnit.SECONDS)).isTrue();
        buttonPressed.countDown();
        press(TEACHER, request.messageId(), request.callbackOf(button).orElseThrow());
        event.get(10, TimeUnit.SECONDS);
        worker.drain();

        assertThat(telegram.callbackAnswers()).singleElement()
                .satisfies(answer -> assertThat(answer.alert()).as("no error alert").isFalse());
        assertThat(core.replies()).hasSize(1);
        assertThat(substitutions.find(CHANGE)).get().extracting(SubstitutionRequest::getStatus).isEqualTo(status);
        assertThat(telegram.editsIn(TEACHER)).isNotEmpty()
                .allSatisfy(edit -> assertThat(edit.messageId()).isEqualTo(request.messageId()));
    }

    /** Some backend is blocked by a lock this transaction's backend holds (PostgreSQL {@code pg_blocking_pids}). */
    private boolean anotherTransactionWaitsForThisOne() {
        Integer blocked = jdbc.queryForObject(
                "select count(*) from pg_stat_activity where pg_backend_pid() = any(pg_blocking_pids(pid))",
                Integer.class);
        return blocked != null && blocked > 0;
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

    /**
     * The change went to another teacher while the request to the first one was still queued (per-chat rate
     * limit, Telegram retry backoff): the first teacher never gets it, and the second one's answer edits
     * only the second one's message.
     */
    @Test
    void reassignmentBeforeDeliveryNeverReachesThePreviousTeacher() {
        teacherChat(2002, CoreFixtures.TEACHER_KRENTSIN, "Кренцін М.Д.");
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        handlers.handle(requested(CoreFixtures.TEACHER_KRENTSIN));
        worker.drain();

        assertThat(telegram.sentTo(TEACHER)).isEmpty();
        assertThat(telegram.lastSentTo(2002).text()).startsWith("🙋 <b>Прохання про заміну</b>");
        assertThat(jdbc.queryForList("select status || ' ' || last_error from outbound_message where chat_id = ?",
                String.class, TEACHER)).singleElement().asString()
                .isEqualTo("FAILED Неактуальне: заміну передано іншому викладачу.");

        handlers.handle(statusChanged(CHANGE, ChangeStatus.CONFIRMED, CoreFixtures.TEACHER_KRENTSIN));
        worker.drain();
        assertThat(telegram.editsIn(TEACHER)).isEmpty();
        assertThat(telegram.lastEditIn(2002).text()).endsWith("✅ <b>Ви підтвердили заміну.</b> Дякуємо!");
    }

    /**
     * Telegram accepted the request to the first teacher while the change was being reassigned (the worker had
     * already checked it): the message is retired at once and is never edited with the second teacher's answer.
     */
    @Test
    void requestDeliveredWhileTheChangeWasReassignedIsRetiredAtOnce() {
        teacherChat(2002, CoreFixtures.TEACHER_KRENTSIN, "Кренцін М.Д.");
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        handlers.handle(requested(CoreFixtures.TEACHER_KRENTSIN));
        delivered(TEACHER, 555, DeliveryPurpose.SUBSTITUTION_REQUEST);
        worker.drain();

        assertThat(telegram.editsIn(TEACHER)).singleElement().satisfies(edit -> {
            assertThat(edit.messageId()).isEqualTo(555);
            assertThat(edit.text()).endsWith("Цей запит уже неактуальний.</b>");
            assertThat(edit.keyboard()).isNull();
        });
        handlers.handle(statusChanged(CHANGE, ChangeStatus.CONFIRMED, CoreFixtures.TEACHER_KRENTSIN));
        worker.drain();
        assertThat(telegram.editsIn(TEACHER)).hasSize(1);
    }

    /** Telegram's 5xx keeps the request in retry backoff while the dispatcher cancels: it is never sent. */
    @Test
    void requestCancelledDuringRetryBackoffIsNotSent() {
        telegram.failNext(new ua.vtc.rozklad.notification.telegram.TelegramApiFailure(502, "Bad Gateway", null, null,
                null));
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();
        handlers.handle(statusChanged(ChangeStatus.CANCELLED));
        clock.advance(Duration.ofSeconds(3));
        worker.drain();

        assertThat(telegram.sentTo(TEACHER)).isEmpty();
        assertThat(telegram.editsIn(TEACHER)).isEmpty();
    }

    /**
     * core re-sends the request once the deadline passed, and notification's every-minute job reminds too: in
     * either order the teacher gets one reminder and admins one notice per change.
     */
    @Test
    void coresDeadlineReminderAndTheJobRemindOnlyOnce() {
        handlers.handle(requested(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE, 2));
        handlers.handle(requested(CHANGE_2, CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE, 3));
        worker.drain();
        clock.set(Instant.parse("2026-10-19T07:01:00Z"));

        handlers.handle(requested(CHANGE_2, CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE, 3)); // core first
        assertThat(substitutions.remindOverdue()).isEqualTo(2);
        handlers.handle(requested(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE, 2)); // the job first
        assertThat(substitutions.remindOverdue()).isZero();
        worker.drain();

        assertThat(telegram.sentTo(TEACHER)).extracting(Sent::text)
                .filteredOn(text -> text.startsWith("⏰")).hasSize(2);
        assertThat(telegram.sentTo(OWNER_CHAT)).extracting(Sent::text)
                .filteredOn(text -> text.startsWith("⏰ <b>Немає відповіді щодо заміни</b>")).hasSize(2);
    }

    /**
     * After the deadline round was reminded, a new room (core re-sends the request) still reaches the teacher as
     * an update; core's later plain deadline reminder of the same round is still deduplicated with the job's.
     */
    @Test
    void newRoomAfterTheDeadlineReminderIsStillSent() {
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();
        clock.set(Instant.parse("2026-10-19T07:01:00Z"));
        assertThat(substitutions.remindOverdue()).isEqualTo(1);
        handlers.handle(requestedInRoom(SubstitutionRequestMode.CONFIRM, "12"));
        worker.drain();

        assertThat(telegram.sentTo(TEACHER)).hasSize(3);
        assertThat(telegram.lastSentTo(TEACHER).text()).startsWith("✏️ <b>Заміну змінено</b>\n"
                + "Нова аудиторія: 12 (була 6)\n\n").contains("🚪 Аудиторія 12");

        handlers.handle(requestedInRoom(SubstitutionRequestMode.CONFIRM, "12")); // core's deadline reminder
        assertThat(substitutions.remindOverdue()).isZero();
        worker.drain();
        assertThat(telegram.sentTo(TEACHER)).hasSize(3);
    }

    /**
     * The dispatcher moved a pending CONFIRM request to another room: the teacher gets one «✏️ Заміну змінено»
     * with the new room, the same deadline and buttons, not a reminder. A plain repeat afterwards is still a
     * reminder, and the answer edits both messages to the new room.
     */
    @Test
    void roomEditOfAPendingRequestIsAnUpdateNotAReminder() {
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();

        handlers.handle(requestedInRoom(SubstitutionRequestMode.CONFIRM, "214"));
        worker.drain();
        assertThat(telegram.sentTo(TEACHER)).hasSize(2);
        Sent update = telegram.lastSentTo(TEACHER);
        assertThat(update.text()).startsWith("✏️ <b>Заміну змінено</b>\nНова аудиторія: 214 (була 6)\n\n📅")
                .contains("🚪 Аудиторія 214")
                .doesNotContain("Нагадування", "Аудиторія 6")
                .endsWith("Будь ласка, дайте відповідь до 19.10 о 10:00.");
        assertThat(update.buttons()).containsExactly("✅ Підтверджую", "❌ Не можу");
        assertThat(jdbc.queryForList("select purpose from outbound_message where chat_id = ? order by created_at",
                String.class, TEACHER)).containsExactly("SUBSTITUTION_REQUEST", "SUBSTITUTION_REQUEST");
        assertThat(substitutions.find(CHANGE)).get().satisfies(request -> {
            assertThat(request.getRoomName()).isEqualTo("214");
            assertThat(request.getDeadline()).isEqualTo(Instant.parse("2026-10-19T07:00:00Z"));
            assertThat(request.isPending()).isTrue();
        });

        handlers.handle(requestedInRoom(SubstitutionRequestMode.CONFIRM, "214"));
        worker.drain();
        assertThat(telegram.sentTo(TEACHER)).hasSize(3);
        assertThat(telegram.lastSentTo(TEACHER).text()).startsWith("⏰ <b>Нагадування: потрібна ваша відповідь</b>")
                .contains("🚪 Аудиторія 214");

        press(TEACHER, update.messageId(), update.callbackOf("✅ Підтверджую").orElseThrow());
        worker.drain();
        assertThat(telegram.editsIn(TEACHER)).hasSize(3).allSatisfy(edit -> assertThat(edit.text())
                .startsWith("🙋 <b>Прохання про заміну</b>").contains("🚪 Аудиторія 214")
                .endsWith("✅ <b>Ви підтвердили заміну.</b> Дякуємо!"));
        assertThat(telegram.sentTo(OWNER_CHAT)).isEmpty();
    }

    /**
     * The same for a NOTIFY notice: «✏️ Заміну змінено» with the new room and [👍 Добре] [❌ Не можу]; a plain
     * repeat stays the usual notice.
     */
    @Test
    void roomEditOfANoticeIsAnUpdateWithTheNoticeButtons() {
        handlers.handle(requestedInRoom(SubstitutionRequestMode.NOTIFY, "6"));
        worker.drain();

        handlers.handle(requestedInRoom(SubstitutionRequestMode.NOTIFY, "214"));
        worker.drain();
        assertThat(telegram.sentTo(TEACHER)).hasSize(2);
        Sent update = telegram.lastSentTo(TEACHER);
        assertThat(update.text()).startsWith("✏️ <b>Заміну змінено</b>\nНова аудиторія: 214 (була 6)\n\n📅")
                .contains("🚪 Аудиторія 214")
                .doesNotContain("Вам поставили заміну", "дайте відповідь до")
                .endsWith("Якщо не можете, натисніть «Не можу» — диспетчер обере інший варіант.");
        assertThat(update.buttons()).containsExactly("👍 Добре", "❌ Не можу");
        assertThat(substitutions.find(CHANGE)).get().extracting(SubstitutionRequest::getRoomName).isEqualTo("214");

        handlers.handle(requestedInRoom(SubstitutionRequestMode.NOTIFY, "214"));
        worker.drain();
        assertThat(telegram.sentTo(TEACHER)).hasSize(3);
        assertThat(telegram.lastSentTo(TEACHER).text()).startsWith("📌 <b>Вам поставили заміну</b>");

        core.answerAsNotice(ChangeStatus.CONFIRMED);
        press(TEACHER, update.messageId(), update.callbackOf("👍 Добре").orElseThrow());
        worker.drain();
        assertThat(telegram.editsIn(TEACHER)).isNotEmpty().allSatisfy(edit -> assertThat(edit.text())
                .contains("🚪 Аудиторія 214").endsWith("👍 <b>Добре, заміну прийнято.</b> Дякуємо!"));
    }

    /**
     * The dispatcher moved a request the teacher already confirmed (substitution.details-changed): one
     * «✏️ Заміну змінено» with the new room, without buttons, a deadline or a request to confirm again; his answer
     * stays. A repeat of the same details sends nothing, and the later final state shows the new room.
     */
    @Test
    void roomEditOfAConfirmedRequestIsAnInfoOnlyUpdate() {
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();
        Sent request = telegram.lastSentTo(TEACHER);
        press(TEACHER, request.messageId(), request.callbackOf("✅ Підтверджую").orElseThrow());
        worker.drain();

        handlers.handle(detailsChanged("214"));
        worker.drain();
        assertThat(telegram.sentTo(TEACHER)).hasSize(2);
        Sent update = telegram.lastSentTo(TEACHER);
        assertThat(update.text()).startsWith("✏️ <b>Заміну змінено</b>\nНова аудиторія: 214 (була 6)\n\n📅")
                .contains("🚪 Аудиторія 214")
                .doesNotContain("Аудиторія 6", "дайте відповідь до", "Нагадування", "Прохання")
                .endsWith("Заміна вже підтверджена, відповідати не потрібно.");
        assertThat(update.buttons()).isEmpty();
        assertThat(jdbc.queryForList("select purpose from outbound_message where chat_id = ? and kind = 'TEXT' "
                + "order by created_at", String.class, TEACHER))
                .containsExactly("SUBSTITUTION_REQUEST", "SUBSTITUTION_STATUS");
        assertThat(substitutions.find(CHANGE)).get().satisfies(found -> {
            assertThat(found.getStatus()).isEqualTo(ChangeStatus.CONFIRMED);
            assertThat(found.getRoomName()).isEqualTo("214");
        });
        assertThat(core.replies()).hasSize(1);

        handlers.handle(detailsChanged("214"));
        worker.drain();
        assertThat(telegram.sentTo(TEACHER)).hasSize(2);

        handlers.handle(statusChanged(ChangeStatus.APPLIED));
        worker.drain();
        assertThat(telegram.lastEditIn(TEACHER).text()).contains("🚪 Аудиторія 214")
                .endsWith("📄 <b>Заміну внесено до розпорядження.</b> Дякуємо!");
        assertThat(telegram.sentTo(OWNER_CHAT)).isEmpty();
    }

    /**
     * A confirmation request for a request already confirmed can only be a late one (core never asks again after
     * CONFIRMED): a plain repeat sends nothing and keeps the request confirmed; one with a new room is the
     * info-only update, never a new request.
     */
    @Test
    void lateRequestForAConfirmedRequestNeverAsksAgain() {
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();
        handlers.handle(statusChanged(ChangeStatus.CONFIRMED));
        worker.drain();

        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();
        assertThat(telegram.sentTo(TEACHER)).hasSize(1);
        assertThat(substitutions.find(CHANGE)).get().extracting(SubstitutionRequest::getStatus)
                .isEqualTo(ChangeStatus.CONFIRMED);

        handlers.handle(requestedInRoom(SubstitutionRequestMode.CONFIRM, "214"));
        worker.drain();
        assertThat(telegram.sentTo(TEACHER)).hasSize(2);
        assertThat(telegram.lastSentTo(TEACHER).text()).startsWith("✏️ <b>Заміну змінено</b>")
                .endsWith("Заміна вже підтверджена, відповідати не потрібно.");
        assertThat(telegram.lastSentTo(TEACHER).buttons()).isEmpty();
        assertThat(substitutions.find(CHANGE)).get().extracting(SubstitutionRequest::getStatus)
                .isEqualTo(ChangeStatus.CONFIRMED);
    }

    /**
     * The info-only update is the newest message in the teacher's chat: a later cancellation edits it too, so
     * whichever message he reads says «скасовано» (with the new room).
     */
    @Test
    void cancellationAfterAnInfoOnlyUpdateEditsItToo() {
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();
        Sent request = telegram.lastSentTo(TEACHER);
        press(TEACHER, request.messageId(), request.callbackOf("✅ Підтверджую").orElseThrow());
        handlers.handle(detailsChanged("214"));
        worker.drain();
        Sent update = telegram.lastSentTo(TEACHER);
        int editsBefore = telegram.editsIn(TEACHER).size();

        handlers.handle(statusChanged(ChangeStatus.CANCELLED));
        worker.drain();
        assertThat(telegram.editsIn(TEACHER).subList(editsBefore, telegram.editsIn(TEACHER).size()))
                .hasSize(2)
                .allSatisfy(edit -> {
                    assertThat(edit.text()).contains("🚪 Аудиторія 214")
                            .endsWith("🚫 <b>Заміну скасовано.</b> Нічого робити не потрібно.");
                    assertThat(edit.keyboard()).isNull();
                })
                .extracting(Edit::messageId).containsExactlyInAnyOrder(request.messageId(), update.messageId());
    }

    /** The change was cancelled before the info-only update left the queue: it is not sent. */
    @Test
    void infoOnlyUpdateOfACancelledChangeIsNotSent() {
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();
        handlers.handle(statusChanged(ChangeStatus.CONFIRMED));
        worker.drain();
        handlers.handle(detailsChanged("214"));
        handlers.handle(statusChanged(ChangeStatus.CANCELLED));
        worker.drain();

        assertThat(telegram.sentTo(TEACHER)).singleElement().extracting(Sent::text).asString().startsWith("🙋");
        assertThat(jdbc.queryForList("select last_error from outbound_message where chat_id = ? and kind = 'TEXT' "
                + "and purpose = 'SUBSTITUTION_STATUS'", String.class, TEACHER))
                .containsExactly(SubstitutionRequests.NOT_IN_FORCE);
    }

    /** The info-only update reached Telegram just as the change was cancelled: it is corrected at once. */
    @Test
    void infoOnlyUpdateDeliveredWhileTheChangeWasCancelledIsCorrectedAtOnce() {
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();
        handlers.handle(statusChanged(ChangeStatus.CONFIRMED));
        worker.drain();
        handlers.handle(detailsChanged("214"));
        handlers.handle(statusChanged(ChangeStatus.CANCELLED));
        delivered(TEACHER, 557, DeliveryPurpose.SUBSTITUTION_STATUS);
        worker.drain();

        assertThat(jdbc.queryForObject("select count(*) from substitution_message where message_id = 557",
                Integer.class)).isEqualTo(1);

        assertThat(telegram.editsIn(TEACHER)).filteredOn(edit -> edit.messageId() == 557).singleElement()
                .satisfies(edit -> {
                    assertThat(edit.text()).contains("🚪 Аудиторія 214").contains("Заміну скасовано");
                    assertThat(edit.keyboard()).isNull();
                });
    }

    /**
     * core published details-changed only for a confirmed request, so a copy still pending here only missed the
     * CONFIRMED event (delayed, or the local bookkeeping of the teacher's tap failed): the request is confirmed
     * here, its message shows it with the new room, and the teacher gets the info-only update; the late CONFIRMED
     * event then changes nothing.
     */
    @Test
    void roomEditOfARequestConfirmedInCoreButPendingHereIsAnInfoOnlyUpdate() {
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();
        Sent request = telegram.lastSentTo(TEACHER);

        handlers.handle(detailsChanged("214"));
        worker.drain();
        assertThat(telegram.editsIn(TEACHER)).singleElement().satisfies(edit -> {
            assertThat(edit.messageId()).isEqualTo(request.messageId());
            assertThat(edit.text()).contains("🚪 Аудиторія 214").endsWith("✅ <b>Ви підтвердили заміну.</b> Дякуємо!");
            assertThat(edit.keyboard()).isNull();
        });
        assertThat(telegram.sentTo(TEACHER)).hasSize(2);
        Sent update = telegram.lastSentTo(TEACHER);
        assertThat(update.text()).startsWith("✏️ <b>Заміну змінено</b>\nНова аудиторія: 214 (була 6)\n\n📅")
                .endsWith("Заміна вже підтверджена, відповідати не потрібно.");
        assertThat(update.buttons()).isEmpty();
        assertThat(substitutions.find(CHANGE)).get().satisfies(found -> {
            assertThat(found.getStatus()).isEqualTo(ChangeStatus.CONFIRMED);
            assertThat(found.getRoomName()).isEqualTo("214");
        });

        handlers.handle(statusChanged(ChangeStatus.CONFIRMED));
        worker.drain();
        assertThat(telegram.sentTo(TEACHER)).hasSize(2);
        assertThat(telegram.editsIn(TEACHER)).hasSize(1);
    }

    /**
     * core re-sends a failed publication a minute later, so the delayed CONFIRMED can come after the change was
     * cancelled (or published): it never moves the copy back, both messages keep «скасовано».
     */
    @Test
    void lateConfirmationAfterTheCancellationLeavesBothMessagesCancelled() {
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();
        Sent request = telegram.lastSentTo(TEACHER);
        handlers.handle(detailsChanged("214"));
        worker.drain();
        Sent update = telegram.lastSentTo(TEACHER);
        handlers.handle(statusChanged(ChangeStatus.CANCELLED));
        worker.drain();
        int editsBefore = telegram.editsIn(TEACHER).size();

        handlers.handle(statusChanged(ChangeStatus.CONFIRMED));
        worker.drain();

        assertThat(telegram.editsIn(TEACHER)).hasSize(editsBefore);
        for (long messageId : new long[] {request.messageId(), update.messageId()}) {
            assertThat(telegram.editsIn(TEACHER)).filteredOn(edit -> edit.messageId() == messageId).last()
                    .extracting(Edit::text).asString()
                    .endsWith("🚫 <b>Заміну скасовано.</b> Нічого робити не потрібно.");
        }
        assertThat(substitutions.find(CHANGE)).get().extracting(SubstitutionRequest::getStatus)
                .isEqualTo(ChangeStatus.CANCELLED);
    }

    /** A late CONFIRMED after the order was published changes nothing either. */
    @Test
    void lateConfirmationAfterThePublicationChangesNothing() {
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();
        handlers.handle(detailsChanged("214"));
        handlers.handle(statusChanged(ChangeStatus.APPLIED));
        worker.drain();
        int editsBefore = telegram.editsIn(TEACHER).size();

        handlers.handle(statusChanged(ChangeStatus.CONFIRMED));
        worker.drain();

        assertThat(telegram.editsIn(TEACHER)).hasSize(editsBefore);
        assertThat(telegram.lastEditIn(TEACHER).text()).endsWith("📄 <b>Заміну внесено до розпорядження.</b> Дякуємо!");
        assertThat(substitutions.find(CHANGE)).get().extracting(SubstitutionRequest::getStatus)
                .isEqualTo(ChangeStatus.APPLIED);
    }

    /** A reminder core re-sends after the cancellation does not reopen the request or reach the teacher. */
    @Test
    void lateReminderOfACancelledRequestIsNotSent() {
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();
        handlers.handle(statusChanged(ChangeStatus.CANCELLED));
        worker.drain();

        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        worker.drain();

        assertThat(telegram.sentTo(TEACHER)).singleElement().extracting(Sent::text).asString().startsWith("🙋");
        assertThat(substitutions.find(CHANGE)).get().extracting(SubstitutionRequest::getStatus)
                .isEqualTo(ChangeStatus.CANCELLED);
    }

    /** Names for admin texts come from core over HTTP: looked up before the event's transaction, never inside. */
    @Test
    void eventHandlersDoNotCallCoreInsideTheTransaction() {
        handlers.handle(requested(CoreFixtures.TEACHER_NEPYIVODA));
        handlers.handle(notice(CHANGE_2, CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE, 3));
        worker.drain();
        flushCaches();
        core.reset();

        handlers.handle(statusChanged(ChangeStatus.DECLINED));
        flushCaches();
        handlers.handle(new SubstitutionTeacherReplied(EventEnvelope.create(java.time.Clock.systemUTC()), CHANGE_2,
                CoreFixtures.TEACHER_NEPYIVODA, ConfirmationDecision.DECLINE, ChangeStatus.APPLIED, Order35.DATE, 3,
                "4КІ1"));
        worker.drain();

        assertThat(core.teacherCalls()).isPositive();
        assertThat(core.teacherCallsInTransaction()).isZero();
        assertThat(telegram.sentTo(OWNER_CHAT)).extracting(Sent::text)
                .anySatisfy(text -> assertThat(text).contains("Непийвода М.В. не може взяти 2 пару"))
                .anySatisfy(text -> assertThat(text).contains("Непийвода М.В. не може взяти 3 пару"));
    }

    /** Records that Telegram accepted a message the worker took before the change moved on (the race window). */
    private void delivered(long chatId, long messageId, DeliveryPurpose purpose) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> substitutions.onSent(
                new OutboundMessageSent(UUID.randomUUID(), purpose, CHANGE, chatId, messageId)));
    }

    // ───────────── NOTIFY: «Вам поставили заміну» з [👍 Добре] [❌ Не можу] ─────────────

    /** The dispatcher assigned and cancelled before the notice left the queue: the teacher gets nothing. */
    @Test
    void noticeCancelledBeforeDeliveryIsNotSent() {
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE, 2));
        handlers.handle(statusChanged(ChangeStatus.CANCELLED));
        worker.drain();

        assertThat(telegram.sentTo(TEACHER)).isEmpty();
        assertThat(jdbc.queryForList("select last_error from outbound_message where chat_id = ?", String.class,
                TEACHER)).singleElement().asString().isEqualTo("Неактуальне: заміна вже не чекає відповіді.");
    }

    /** The notice reached Telegram just as the change was cancelled: it is corrected at once, without buttons. */
    @Test
    void noticeDeliveredWhileTheChangeWasCancelledIsCorrectedAtOnce() {
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE, 2));
        handlers.handle(statusChanged(ChangeStatus.CANCELLED));
        delivered(TEACHER, 556, DeliveryPurpose.SUBSTITUTION_NOTICE);
        worker.drain();

        assertThat(telegram.editsIn(TEACHER)).singleElement().satisfies(edit -> {
            assertThat(edit.messageId()).isEqualTo(556);
            assertThat(edit.text()).startsWith("📌").contains("Заміну скасовано");
            assertThat(edit.keyboard()).isNull();
        });
    }

    /** A same-day entry for a pair that already ended (accounting) sends nothing; a pair still running does. */
    @Test
    void noticeForAPairThatAlreadyEndedTodayIsNotSent() {
        clock.set(Instant.parse("2026-10-19T12:00:00Z")); // 15:00 у Києві, 2 пара 09:40–10:40 минула
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE, 2));
        worker.drain();
        assertThat(telegram.sentTo(TEACHER)).isEmpty();

        clock.set(Instant.parse("2026-10-19T07:00:00Z")); // 10:00, пара ще йде
        handlers.handle(notice(CHANGE_2, CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE, 2));
        worker.drain();
        assertThat(telegram.sentTo(TEACHER)).hasSize(1);
    }

    /**
     * CONFIRM: a request for a pair that is already over (an earlier day, or today after its end) is not sent,
     * and neither the teacher nor admins get a reminder about it; a pair that ends later still gets both.
     */
    @Test
    void requestAndReminderForAPairThatAlreadyEndedAreNotSent() {
        clock.set(Instant.parse("2026-10-19T09:00:00Z")); // 12:00 у Києві, 2 пара 09:40–10:40 минула
        handlers.handle(requested(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE, 2,
                Instant.parse("2026-10-19T09:30:00Z")));
        handlers.handle(requested(CHANGE_2, CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE.minusDays(3), 2,
                Instant.parse("2026-10-19T09:30:00Z")));
        worker.drain();
        assertThat(telegram.sentTo(TEACHER)).isEmpty();

        // строк минув, core нагадує тим самим запитом — і все одно нічого
        clock.set(Instant.parse("2026-10-19T09:31:00Z"));
        handlers.handle(requested(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE, 2,
                Instant.parse("2026-10-19T09:30:00Z")));
        substitutions.remindOverdue();
        worker.drain();
        assertThat(telegram.sentTo(TEACHER)).isEmpty();
        assertThat(telegram.sentTo(OWNER_CHAT)).isEmpty();

        // пара ще попереду (завтра): прохання й нагадування йдуть, як і раніше
        handlers.handle(requested(CHANGE_3, CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE.plusDays(1), 2,
                Instant.parse("2026-10-19T09:40:00Z")));
        worker.drain();
        assertThat(telegram.sentTo(TEACHER)).singleElement().extracting(Sent::text).asString()
                .startsWith("🙋 <b>Прохання про заміну</b>");
        clock.set(Instant.parse("2026-10-19T09:41:00Z"));
        assertThat(substitutions.remindOverdue()).isPositive();
        worker.drain();
        assertThat(telegram.lastSentTo(TEACHER).text()).startsWith("⏰ <b>Нагадування");
        assertThat(telegram.sentTo(OWNER_CHAT)).singleElement().extracting(Sent::text).asString()
                .startsWith("⏰ <b>Немає відповіді щодо заміни</b>");
    }

    @Test
    void noticeIsAnsweredWithOkAndTheChangeStays() {
        handlers.handle(statusChanged(ChangeStatus.CONFIRMED));
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE, 2));
        worker.drain();

        Sent notice = telegram.lastSentTo(TEACHER);
        assertThat(notice.text()).startsWith("📌 <b>Вам поставили заміну</b>")
                .contains("🔁 Замість «Сучасні технології проєктування» (Кренцін М.Д.) (взаємна заміна)")
                .doesNotContain("дайте відповідь до")
                .endsWith("Якщо не можете, натисніть «Не можу» — диспетчер обере інший варіант.");
        assertThat(notice.buttons()).containsExactly("👍 Добре", "❌ Не можу");
        assertThat(jdbc.queryForObject("select purpose from outbound_message where correlation_id = ? and kind = 'TEXT'",
                String.class, CHANGE)).isEqualTo("SUBSTITUTION_NOTICE");

        core.answerAsNotice(ChangeStatus.CONFIRMED);
        press(TEACHER, notice.messageId(), notice.callbackOf("👍 Добре").orElseThrow());
        assertThat(core.replies()).containsExactly(new Reply(CHANGE, CoreFixtures.TEACHER_NEPYIVODA,
                ConfirmationDecision.CONFIRM));
        Edit done = telegram.lastEditIn(TEACHER);
        assertThat(done.messageId()).isEqualTo(notice.messageId());
        assertThat(done.text()).endsWith("👍 <b>Добре, заміну прийнято.</b> Дякуємо!");
        assertThat(done.keyboard()).isNull();
        assertThat(telegram.lastCallbackAnswer().text()).isEqualTo("Дякуємо! 👍");
        assertThat(substitutions.find(CHANGE)).get().satisfies(request -> {
            assertThat(request.getStatus()).isEqualTo(ChangeStatus.CONFIRMED);
            assertThat(request.getTeacherAnswer()).isEqualTo(ConfirmationDecision.CONFIRM);
        });

        // подія core про ту саму відповідь нічого вже не змінює; публікація прибирає «Дякуємо» на «внесено»
        handlers.handle(teacherReplied(ConfirmationDecision.CONFIRM, ChangeStatus.CONFIRMED));
        worker.drain();
        assertThat(telegram.editsIn(TEACHER)).hasSize(1);
        handlers.handle(statusChanged(ChangeStatus.APPLIED));
        worker.drain();
        assertThat(telegram.lastEditIn(TEACHER).text()).endsWith("📄 <b>Заміну внесено до розпорядження.</b> Дякуємо!");
        assertThat(telegram.lastEditIn(TEACHER).keyboard()).isNull();
        assertThat(telegram.sentTo(OWNER_CHAT)).isEmpty();
    }

    @Test
    void cannotBeforeTheOrderIsADeclineTheDispatcherHearsAbout() {
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE, 2));
        worker.drain();
        Sent notice = telegram.lastSentTo(TEACHER);

        core.answerAsNotice(ChangeStatus.CONFIRMED);
        press(TEACHER, notice.messageId(), notice.callbackOf("❌ Не можу").orElseThrow());
        assertThat(telegram.lastEditIn(TEACHER).text()).startsWith("📌 <b>Вам поставили заміну</b>")
                .endsWith("❌ <b>Ви відмовилися від заміни.</b> Диспетчер обере інший варіант.");
        assertThat(telegram.lastCallbackAnswer().text()).isEqualTo("Відмову передано диспетчеру");

        handlers.handle(statusChanged(ChangeStatus.DECLINED));
        worker.drain();
        assertThat(telegram.editsIn(TEACHER)).hasSize(1);
        assertThat(telegram.sentTo(OWNER_CHAT)).singleElement().extracting(Sent::text).asString()
                .isEqualTo("❌ <b>Відмова від заміни</b>\nНепийвода М.В. не може взяти 2 пару 4КІ1 у понеділок, "
                        + "19 жовтня. Оберіть інший варіант на сторінці змін.");

        // «Добре» після відмови: core каже 409, повідомлення лишає підсумок
        core.failReplies(new UpstreamRejectedException(409, "Ви вже відмовилися від цієї заміни."));
        press(TEACHER, notice.messageId(), notice.callbackOf("👍 Добре").orElseThrow());
        assertThat(telegram.lastCallbackAnswer().text()).isEqualTo("Цей запит уже неактуальний");
        assertThat(telegram.lastEditIn(TEACHER).text()).endsWith("Диспетчер обере інший варіант.");
    }

    @Test
    void publishedNoticeKeepsItsButtonsAndAnObjectionAsksForAnAmendment() {
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE, 2));
        worker.drain();
        Sent notice = telegram.lastSentTo(TEACHER);

        handlers.handle(statusChanged(ChangeStatus.APPLIED));
        worker.drain();
        Edit published = telegram.lastEditIn(TEACHER);
        assertThat(published.text()).endsWith("📄 Заміну внесено до розпорядження.");
        assertThat(published.buttons()).as("still answerable").containsExactly("👍 Добре", "❌ Не можу");

        core.answerAsNotice(ChangeStatus.APPLIED);
        press(TEACHER, notice.messageId(), notice.callbackOf("❌ Не можу").orElseThrow());
        assertThat(telegram.lastEditIn(TEACHER).text()).endsWith("❗ <b>Ви повідомили, що не можете.</b> Заміна вже "
                + "в розпорядженні, тож диспетчера повідомлено: він внесе доповнення.");
        assertThat(telegram.lastEditIn(TEACHER).keyboard()).isNull();
        assertThat(telegram.lastCallbackAnswer().text()).isEqualTo("Диспетчера повідомлено");
        assertThat(telegram.sentTo(OWNER_CHAT)).as("admins hear it from core's event").isEmpty();

        SubstitutionTeacherReplied objection = teacherReplied(ConfirmationDecision.DECLINE, ChangeStatus.APPLIED);
        handlers.handle(objection);
        handlers.handle(objection);
        worker.drain();
        assertThat(telegram.sentTo(OWNER_CHAT)).singleElement().extracting(Sent::text).asString()
                .startsWith("❗ <b>Потрібне доповнення до розпорядження</b>\nНепийвода М.В. не може взяти 2 пару 4КІ1 "
                        + "у понеділок, 19 жовтня, а заміна вже в опублікованому розпорядженні.");
        assertThat(telegram.editsIn(TEACHER)).hasSize(2);
        assertThat(substitutions.find(CHANGE)).get().satisfies(request -> {
            assertThat(request.getStatus()).isEqualTo(ChangeStatus.APPLIED);
            assertThat(request.getTeacherAnswer()).isEqualTo(ConfirmationDecision.DECLINE);
        });
    }

    /** «Добре» in Telegram, then «Не можу» in the cabinet: core's DECLINED event records the new answer locally. */
    @Test
    void cannotFromTheCabinetAfterOkIsRecordedAsTheAnswer() {
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE, 2));
        worker.drain();
        Sent notice = telegram.lastSentTo(TEACHER);
        core.answerAsNotice(ChangeStatus.CONFIRMED);
        press(TEACHER, notice.messageId(), notice.callbackOf("👍 Добре").orElseThrow());

        handlers.handle(statusChanged(ChangeStatus.DECLINED));
        worker.drain();

        assertThat(substitutions.find(CHANGE)).get().satisfies(request -> {
            assertThat(request.getStatus()).isEqualTo(ChangeStatus.DECLINED);
            assertThat(request.getTeacherAnswer()).isEqualTo(ConfirmationDecision.DECLINE);
        });
        assertThat(telegram.lastEditIn(TEACHER).text()).endsWith("Диспетчер обере інший варіант.");
        assertThat(telegram.sentTo(OWNER_CHAT)).singleElement().extracting(Sent::text).asString()
                .startsWith("❌ <b>Відмова від заміни</b>");
    }

    /** The teacher answered in the cabinet: core's event edits the Telegram notice and alerts admins. */
    @Test
    void objectionFromTheCabinetEditsTheNoticeToo() {
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE, 2));
        handlers.handle(statusChanged(ChangeStatus.APPLIED));
        worker.drain();

        handlers.handle(teacherReplied(ConfirmationDecision.DECLINE, ChangeStatus.APPLIED));
        worker.drain();

        assertThat(telegram.lastEditIn(TEACHER).text()).endsWith("диспетчера повідомлено: він внесе доповнення.");
        assertThat(telegram.lastEditIn(TEACHER).keyboard()).isNull();
        assertThat(telegram.sentTo(OWNER_CHAT)).singleElement().extracting(Sent::text).asString()
                .startsWith("❗ <b>Потрібне доповнення до розпорядження</b>");
    }

    @Test
    void noticeToATeacherWithoutTelegramAsksAdminsToTellHim() {
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_KRENTSIN, Order35.DATE, 2));
        handlers.handle(notice(CHANGE_2, CoreFixtures.TEACHER_KRENTSIN, Order35.DATE, 3));
        handlers.handle(statusChanged(CHANGE_2, ChangeStatus.APPLIED, CoreFixtures.TEACHER_KRENTSIN));
        // відповів у кабінеті до попередження — про цю зміну мовчимо
        handlers.handle(notice(CHANGE_3, CoreFixtures.TEACHER_KRENTSIN, Order35.DATE, 4));
        handlers.handle(new SubstitutionTeacherReplied(EventEnvelope.create(java.time.Clock.systemUTC()), CHANGE_3,
                CoreFixtures.TEACHER_KRENTSIN, ConfirmationDecision.CONFIRM, ChangeStatus.CONFIRMED, Order35.DATE, 4,
                "4КІ1"));
        // минулий день: нічого не надсилаємо і не попереджаємо
        handlers.handle(notice(CHANGE_4, CoreFixtures.TEACHER_KRENTSIN, Order35.DATE.minusDays(3), 2));
        assertThat(substitutions.reportTeachersWithoutTelegram()).isZero();

        clock.advance(NOTICE_DELAY);
        assertThat(substitutions.reportTeachersWithoutTelegram()).isEqualTo(1);
        assertThat(substitutions.reportTeachersWithoutTelegram()).isZero();
        worker.drain();
        assertThat(telegram.sentTo(OWNER_CHAT)).singleElement().extracting(Sent::text).asString()
                .isEqualTo("⚠️ <b>Кренцін М.Д. не підключив Telegram — повідомте про заміну самі</b>\n"
                        + "Повідомлення не надіслано, понеділок, 19 жовтня:\n"
                        + "• 2 пара · 4КІ1 · Надійність, діагностика та експлуатація комп'ютерних систем і мереж\n"
                        + "• 3 пара · 4КІ1 · Надійність, діагностика та експлуатація комп'ютерних систем і мереж");
        // без строку немає нагадувань
        clock.advance(Duration.ofDays(1));
        assertThat(substitutions.remindOverdue()).isZero();
    }

    @Test
    void pastLessonGetsNoNotice() {
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE.minusDays(1), 2));
        worker.drain();
        assertThat(telegram.sentTo(TEACHER)).isEmpty();
    }

    @Test
    void okRacingWithCoresEventNeverShowsAnError() throws Exception {
        raceNoticeButtonWithEvent("👍 Добре", ChangeStatus.CONFIRMED,
                teacherReplied(ConfirmationDecision.CONFIRM, ChangeStatus.CONFIRMED));
        assertThat(telegram.lastCallbackAnswer().text()).isEqualTo("Дякуємо! 👍");
        assertThat(telegram.editsIn(TEACHER))
                .allSatisfy(edit -> assertThat(edit.text()).endsWith("👍 <b>Добре, заміну прийнято.</b> Дякуємо!"));
        assertThat(telegram.sentTo(OWNER_CHAT)).isEmpty();
    }

    @Test
    void cannotRacingWithTheDeclineEventNeverShowsAnError() throws Exception {
        raceNoticeButtonWithEvent("❌ Не можу", ChangeStatus.CONFIRMED, statusChanged(ChangeStatus.DECLINED));
        assertThat(telegram.lastCallbackAnswer().text()).isEqualTo("Відмову передано диспетчеру");
        assertThat(telegram.editsIn(TEACHER))
                .allSatisfy(edit -> assertThat(edit.text()).endsWith("Диспетчер обере інший варіант."));
        assertThat(telegram.sentTo(OWNER_CHAT)).singleElement().extracting(Sent::text).asString()
                .startsWith("❌ <b>Відмова від заміни</b>");
    }

    @Test
    void objectionRacingWithTheObjectionEventNeverShowsAnError() throws Exception {
        raceNoticeButtonWithEvent("❌ Не можу", ChangeStatus.APPLIED,
                teacherReplied(ConfirmationDecision.DECLINE, ChangeStatus.APPLIED));
        assertThat(telegram.lastCallbackAnswer().text()).isEqualTo("Диспетчера повідомлено");
        assertThat(telegram.editsIn(TEACHER))
                .allSatisfy(edit -> assertThat(edit.text()).endsWith("він внесе доповнення."));
        assertThat(telegram.sentTo(OWNER_CHAT)).singleElement().extracting(Sent::text).asString()
                .startsWith("❗ <b>Потрібне доповнення до розпорядження</b>");
        assertThat(substitutions.find(CHANGE)).get().extracting(SubstitutionRequest::getTeacherAnswer)
                .isEqualTo(ConfirmationDecision.DECLINE);
    }

    /**
     * Like {@link #raceButtonWithStatusEvent}: core's event for the very answer is flushed (not committed) while
     * the teacher's button records the same answer; the button waits for the row and then sees the event's state.
     */
    private void raceNoticeButtonWithEvent(String button, ChangeStatus statusBefore, RozkladEvent coreEvent)
            throws Exception {
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE, 2));
        if (statusBefore == ChangeStatus.APPLIED) {
            handlers.handle(statusChanged(ChangeStatus.APPLIED));
        }
        worker.drain();
        Sent notice = telegram.lastSentTo(TEACHER);
        core.answerAsNotice(statusBefore);
        int editsBefore = telegram.editsIn(TEACHER).size();

        CountDownLatch eventFlushed = new CountDownLatch(1);
        CountDownLatch buttonPressed = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CompletableFuture<Void> event = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(tx -> {
            handlers.handle(coreEvent);
            requestRepository.flush();
            eventFlushed.countDown();
            try {
                assertThat(buttonPressed.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            await(Duration.ofSeconds(10), this::anotherTransactionWaitsForThisOne);
        }));
        assertThat(eventFlushed.await(10, TimeUnit.SECONDS)).isTrue();
        buttonPressed.countDown();
        press(TEACHER, notice.messageId(), notice.callbackOf(button).orElseThrow());
        event.get(10, TimeUnit.SECONDS);
        worker.drain();

        assertThat(telegram.callbackAnswers()).singleElement()
                .satisfies(answer -> assertThat(answer.alert()).as("no error alert").isFalse());
        assertThat(core.replies()).hasSize(1);
        // усі редагування стосуються натиснутого повідомлення: і подія, і кнопка приходять до одного стану
        assertThat(telegram.editsIn(TEACHER).subList(editsBefore, telegram.editsIn(TEACHER).size())).isNotEmpty()
                .allSatisfy(edit -> assertThat(edit.messageId()).isEqualTo(notice.messageId()));
    }

    private static SubstitutionConfirmationRequested notice(UUID changeId, UUID teacherId, LocalDate date, int pair) {
        return new SubstitutionConfirmationRequested(EventEnvelope.create(java.time.Clock.systemUTC()), changeId,
                teacherId, date, pair, LocalTime.of(9, 40), LocalTime.of(10, 40), "4КІ1", 0,
                "Надійність, діагностика та експлуатація комп'ютерних систем і мереж", "Кренцін М.Д.", "6", null,
                SubstitutionRequestMode.NOTIFY, ChangeKind.REPLACE_DISCIPLINE, "Сучасні технології проєктування",
                "MUTUAL");
    }

    private static SubstitutionTeacherReplied teacherReplied(ConfirmationDecision decision, ChangeStatus status) {
        return new SubstitutionTeacherReplied(EventEnvelope.create(java.time.Clock.systemUTC()), CHANGE,
                CoreFixtures.TEACHER_NEPYIVODA, decision, status, Order35.DATE, 2, "4КІ1");
    }

    /** core's re-sent request of {@link #CHANGE} for the same teacher and deadline, in another room. */
    private static SubstitutionConfirmationRequested requestedInRoom(SubstitutionRequestMode mode, String room) {
        boolean notice = mode == SubstitutionRequestMode.NOTIFY;
        return new SubstitutionConfirmationRequested(EventEnvelope.create(java.time.Clock.systemUTC()), CHANGE,
                CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE, 2, LocalTime.of(9, 40), LocalTime.of(10, 40), "4КІ1", 0,
                "Надійність, діагностика та експлуатація комп'ютерних систем і мереж", "Кренцін М.Д.", room,
                notice ? null : Instant.parse("2026-10-19T07:00:00Z"), mode, null, null, null);
    }

    /** core: {@link #CHANGE}, already confirmed, moved to another room (information only). */
    private static SubstitutionDetailsChanged detailsChanged(String room) {
        return new SubstitutionDetailsChanged(EventEnvelope.create(java.time.Clock.systemUTC()), CHANGE,
                CoreFixtures.TEACHER_NEPYIVODA, Order35.DATE, 2, LocalTime.of(9, 40), LocalTime.of(10, 40), "4КІ1", 0,
                "Надійність, діагностика та експлуатація комп'ютерних систем і мереж", "Кренцін М.Д.", room, null,
                null, null);
    }

    private static SubstitutionConfirmationRequested requested(UUID teacherId) {
        return requested(CHANGE, teacherId, Order35.DATE, 2);
    }

    private static SubstitutionConfirmationRequested requested(UUID changeId, UUID teacherId, LocalDate date,
                                                               int pair) {
        return requested(changeId, teacherId, date, pair, Instant.parse("2026-10-19T07:00:00Z"));
    }

    private static SubstitutionConfirmationRequested requested(UUID changeId, UUID teacherId, LocalDate date,
                                                               int pair, Instant deadline) {
        return new SubstitutionConfirmationRequested(EventEnvelope.create(java.time.Clock.systemUTC()), changeId,
                teacherId, date, pair, LocalTime.of(9, 40), LocalTime.of(10, 40), "4КІ1", 0,
                "Надійність, діагностика та експлуатація комп'ютерних систем і мереж", "Кренцін М.Д.", "6",
                deadline);
    }

    private static SubstitutionStatusChanged statusChanged(ChangeStatus status) {
        return statusChanged(CHANGE, status, CoreFixtures.TEACHER_NEPYIVODA);
    }

    private static SubstitutionStatusChanged statusChanged(UUID changeId, ChangeStatus status, UUID teacherId) {
        return new SubstitutionStatusChanged(EventEnvelope.create(java.time.Clock.systemUTC()), changeId, status,
                teacherId, Order35.DATE, "4КІ1");
    }
}
