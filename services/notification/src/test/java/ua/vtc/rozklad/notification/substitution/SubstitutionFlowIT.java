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
import ua.vtc.rozklad.contracts.events.SubstitutionStatusChanged;
import ua.vtc.rozklad.contracts.events.SubstitutionTeacherReplied;
import ua.vtc.rozklad.contracts.events.RozkladEvent;
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

    // ───────────── NOTIFY: «Вам поставили заміну» з [👍 Добре] [❌ Не можу] ─────────────

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
