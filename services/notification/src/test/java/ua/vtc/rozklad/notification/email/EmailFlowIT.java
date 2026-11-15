package ua.vtc.rozklad.notification.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import ua.vtc.rozklad.contracts.AbsenceSource;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.SubstitutionRequestMode;
import ua.vtc.rozklad.contracts.events.AbsenceReported;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.contracts.events.GenerationCompleted;
import ua.vtc.rozklad.contracts.events.OrderPublished;
import ua.vtc.rozklad.contracts.events.SubstitutionConfirmationRequested;
import ua.vtc.rozklad.contracts.events.SubstitutionStatusChanged;
import ua.vtc.rozklad.notification.binding.BindingTarget;
import ua.vtc.rozklad.notification.core.CoreDtos.MailRecipient;
import ua.vtc.rozklad.notification.events.EventHandlers;
import ua.vtc.rozklad.notification.substitution.SubstitutionRequests;
import ua.vtc.rozklad.notification.support.CoreFixtures;
import ua.vtc.rozklad.notification.support.FakeTelegramClient.Sent;
import ua.vtc.rozklad.notification.support.IntegrationTest;
import ua.vtc.rozklad.notification.support.Order35;

/**
 * Every e-mail hook end to end: event → intent in the event's transaction → recipients from core → letter in
 * {@code FakeMailTransport}; settle delay and replacement, relevance, settings, unverified addresses, core and SMTP
 * failures, idempotency — with Telegram working as before next to it.
 */
@ExtendWith(OutputCaptureExtension.class)
class EmailFlowIT extends IntegrationTest {

    private static final long TEACHER_CHAT = 2001;
    private static final UUID CHANGE = Order35.CHANGE_4KI1;
    private static final UUID NEPYIVODA_ACCOUNT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID KRENTSIN_ACCOUNT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID ADMIN_ACCOUNT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID SECOND_ADMIN = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String NEPYIVODA = "nepyivoda@vtc.vn.ua";
    private static final String KRENTSIN = "krentsin@vtc.vn.ua";
    private static final String DISPATCHER = "dispatcher@vtc.vn.ua";
    private static final Duration SETTLE = Duration.ofMinutes(2);
    private static final String DISCIPLINE = "Надійність, діагностика та експлуатація комп'ютерних систем і мереж";

    @Autowired
    private EventHandlers handlers;
    @Autowired
    private SubstitutionRequests substitutions;
    @Autowired
    private EmailMessageRepository letters;
    @Autowired
    private EmailNoticeRepository intents;
    @Autowired
    private EmailPreferences preferences;

    @BeforeEach
    void people() {
        owner();
        teacherChat(TEACHER_CHAT, CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В.");
        core.addMailRecipient(person(NEPYIVODA_ACCOUNT, CoreFixtures.TEACHER_NEPYIVODA, "Непийвода Марія Василівна",
                NEPYIVODA, false, "TEACHER"));
        core.addMailRecipient(person(KRENTSIN_ACCOUNT, CoreFixtures.TEACHER_KRENTSIN, "Кренцін Михайло",
                KRENTSIN, true, "TEACHER"));
        core.addMailRecipient(person(ADMIN_ACCOUNT, null, "Диспетчер", DISPATCHER, false, "ADMIN"));
    }

    @Test
    void aNoticeReachesTheTeacherByMailAfterTheSettleDelayAndTelegramAsBefore() {
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, "6"));
        worker.drain();
        assertThat(telegram.lastSentTo(TEACHER_CHAT).text()).startsWith("📌 <b>Вам поставили заміну</b>");

        mailWorker.drain();
        assertThat(mail.sent()).as("waits for the settle delay").isEmpty();
        assertThat(letters.findAll()).singleElement().returns(EmailMessageStatus.QUEUED, EmailMessage::getStatus);

        clock.advance(SETTLE);
        mailWorker.drain();
        OutgoingMail letter = single(NEPYIVODA);
        assertThat(letter.subject()).isEqualTo("Вам поставили заміну: пн, 19 жовтня, 2 пара, 4КІ1");
        assertThat(letter.toName()).isEqualTo("Непийвода Марія Василівна");
        assertThat(letter.text()).contains("Добрий день, Непийвода Марія Василівна!")
                .contains("Пара: 2 пара · 09:40–10:40\nГрупа: 4КІ1\nДисципліна: " + DISCIPLINE + "\nАудиторія: 6\n"
                        + "Замість: Кренцін М.Д.\n")
                .contains("Відкрити кабінет: http://localhost:5180/me?change=" + CHANGE);
        assertThat(letter.threadId()).isEqualTo("change-" + CHANGE + "@rozklad.local");
        assertThat(letter.messageId()).endsWith("@rozklad.local");
        assertThat(letter.unsubscribeUrl()).isEqualTo("http://localhost:5180/me/notifications");
        assertThat(letters.findAll()).singleElement().returns(EmailMessageStatus.SENT, EmailMessage::getStatus);
        assertThat(core.mailRecipientCallsInTransaction()).as("core is never asked under a row lock").isZero();
    }

    @Test
    void aTeacherWithoutTelegramGetsTheRequestByMail() {
        handlers.handle(request(CHANGE, CoreFixtures.TEACHER_KRENTSIN, Instant.parse("2026-10-19T07:00:00Z")));
        worker.drain();
        assertThat(telegram.sentTo(TEACHER_CHAT)).isEmpty();

        deliverMail();
        assertThat(single(KRENTSIN).subject()).isEqualTo("Підтвердіть заміну до 10:00: пн, 19 жовтня, 2 пара, 4КІ1");
        assertThat(single(KRENTSIN).text()).contains("Відповісти до: пн, 19 жовтня, 10:00");
    }

    @Test
    void aQuickRoomChangeGivesOneLetterWithTheFinalRoom() {
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, "6"));
        mailWorker.drain();
        clock.advance(Duration.ofMinutes(1));
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, "214"));
        deliverMail();

        OutgoingMail letter = single(NEPYIVODA);
        assertThat(letter.subject()).startsWith("Вам поставили заміну:");
        assertThat(letter.text()).contains("Аудиторія: 214").doesNotContain("Аудиторія: 6");
        assertThat(letters.findAll()).extracting(EmailMessage::getStatus)
                .containsExactlyInAnyOrder(EmailMessageStatus.SUPERSEDED, EmailMessageStatus.SENT);
    }

    @Test
    void aNoticeCancelledWithinTheDelaySendsNothing() {
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, "6"));
        mailWorker.drain();
        handlers.handle(statusChanged(CHANGE, ChangeStatus.CANCELLED, CoreFixtures.TEACHER_NEPYIVODA));
        deliverMail();

        assertThat(mail.sent()).isEmpty();
        assertThat(letters.findAll()).singleElement()
                .returns(EmailMessageStatus.SUPERSEDED, EmailMessage::getStatus);
    }

    @Test
    void afterTheNoticeWentOutAChangeAndTheCancellationFollow() {
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, "6"));
        deliverMail();
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, "214"));
        deliverMail();
        handlers.handle(statusChanged(CHANGE, ChangeStatus.CANCELLED, CoreFixtures.TEACHER_NEPYIVODA));
        deliverMail();

        assertThat(mail.sentTo(NEPYIVODA)).extracting(OutgoingMail::subject).containsExactly(
                "Вам поставили заміну: пн, 19 жовтня, 2 пара, 4КІ1",
                "Заміну змінено: пн, 19 жовтня, 2 пара, 4КІ1",
                "Заміну скасовано: пн, 19 жовтня, 2 пара, 4КІ1");
        assertThat(mail.sentTo(NEPYIVODA).get(1).text()).contains("Нова аудиторія: 214 (була 6).")
                .contains(MailTexts.NOTICE_HINT);
        assertThat(mail.sentTo(NEPYIVODA).get(2).text()).contains("(більше не актуально)");
        // a pure repeat of the notice writes nothing new
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, "214"));
        deliverMail();
        assertThat(mail.sentTo(NEPYIVODA)).hasSize(3);
    }

    @Test
    void aNoticeAnsweredInTelegramDuringTheDelayIsNotSent() {
        core.answerAsNotice(ChangeStatus.CONFIRMED);
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, "6"));
        worker.drain();
        Sent notice = telegram.lastSentTo(TEACHER_CHAT);
        press(TEACHER_CHAT, notice.messageId(), notice.callbackOf("👍 Добре").orElseThrow());

        deliverMail();
        assertThat(mail.sent()).isEmpty();
        assertThat(letters.findAll()).singleElement()
                .returns(EmailMessageStatus.FAILED, EmailMessage::getStatus)
                .returns("Неактуальне: заміна вже не чекає відповіді.", EmailMessage::getLastError);
    }

    @Test
    void theChangeMovedToAnotherTeacherTellsThePreviousOne() {
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, "6"));
        deliverMail();
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_KRENTSIN, "6"));
        deliverMail();

        assertThat(mail.sentTo(NEPYIVODA)).extracting(OutgoingMail::subject).containsExactly(
                "Вам поставили заміну: пн, 19 жовтня, 2 пара, 4КІ1",
                "Заміну передано іншому викладачу: пн, 19 жовтня, 2 пара, 4КІ1");
        assertThat(single(KRENTSIN).subject()).startsWith("Вам поставили заміну:");
    }

    @Test
    void switchedOffUnverifiedOrMissingAddressesGetNothingAndTheLogNeverShowsThem(CapturedOutput output) {
        core.reset();
        core.addMailRecipient(person(NEPYIVODA_ACCOUNT, CoreFixtures.TEACHER_NEPYIVODA, "Непийвода",
                "maria.private@gmail.com", false, "TEACHER"));
        core.addMailRecipient(person(KRENTSIN_ACCOUNT, CoreFixtures.TEACHER_KRENTSIN, "Кренцін", null, false,
                "TEACHER"));
        UUID optedOut = UUID.fromString("55555555-5555-4555-8555-555555555555");
        UUID teacher = UUID.fromString("6f7a8b9c-0d1e-4f2a-8b3c-4d5e6f7a8b9c");
        core.addMailRecipient(person(optedOut, teacher, "Вимкнув", "off@vtc.vn.ua", true, "TEACHER"));
        preferences.update(optedOut, true, Map.of(EmailKind.SUBSTITUTION_REQUEST, false), null);

        handlers.handle(request(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, null));
        handlers.handle(request(UUID.randomUUID(), CoreFixtures.TEACHER_KRENTSIN, null));
        handlers.handle(request(UUID.randomUUID(), teacher, null));
        deliverMail();

        assertThat(mail.sent()).isEmpty();
        assertThat(letters.count()).isZero();
        assertThat(intents.findAll()).hasSize(3).allSatisfy(intent ->
                assertThat(intent.getStatus()).isEqualTo(EmailNoticeStatus.EXPANDED));
        assertThat(output.getOut()).contains(NEPYIVODA_ACCOUNT + ": unverified", KRENTSIN_ACCOUNT + ": no_address",
                        optedOut + ": opted_out")
                .doesNotContain("maria.private", "off@vtc.vn.ua");
    }

    @Test
    void coreDownRetriesTheIntentWhileTelegramGoesOn() {
        core.setMailRecipientsUnavailable(true);
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, "6"));
        worker.drain();
        assertThat(telegram.sentTo(TEACHER_CHAT)).hasSize(1);
        mailWorker.drain();
        assertThat(intents.findAll()).singleElement()
                .returns(EmailNoticeStatus.RETRYING, EmailNotice::getStatus)
                .satisfies(intent -> assertThat(intent.getLastError()).startsWith("Не вдалося дізнатися адресатів"));

        core.setMailRecipientsUnavailable(false);
        clock.advance(Duration.ofSeconds(30));
        deliverMail();
        assertThat(single(NEPYIVODA).subject()).startsWith("Вам поставили заміну:");
    }

    @Test
    void aRedeliveredEventQueuesOneLetter() {
        SubstitutionConfirmationRequested event = notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, "6");
        handlers.handle(event);
        handlers.handle(event);
        deliverMail();
        assertThat(mail.sentTo(NEPYIVODA)).hasSize(1);
        assertThat(intents.count()).isOne();
    }

    @Test
    void smtpFailuresAreRetriedAndARefusedRecipientFailsAtOnce() {
        mail.failNext(MailTransportException.transientFailure("SMTP недоступний: timeout"));
        handlers.handle(notice(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, "6"));
        deliverMail();
        assertThat(letters.findAll()).singleElement()
                .returns(EmailMessageStatus.RETRYING, EmailMessage::getStatus)
                .returns(1, EmailMessage::getAttempts);
        clock.advance(Duration.ofSeconds(30));
        mailWorker.drain();
        assertThat(mail.sentTo(NEPYIVODA)).hasSize(1);

        mail.failNext(new MailTransportException("Сервер відхилив адресата: 550 5.1.1 <k•••@vtc.vn.ua>", true, false,
                550, null));
        handlers.handle(request(UUID.randomUUID(), CoreFixtures.TEACHER_KRENTSIN, null));
        deliverMail();
        assertThat(mail.sentTo(KRENTSIN)).isEmpty();
        assertThat(letters.findAll()).filteredOn(letter -> letter.getToAddress().equals(KRENTSIN)).singleElement()
                .returns(EmailMessageStatus.FAILED, EmailMessage::getStatus);
    }

    @Test
    void aFinishedGenerationReachesEveryDispatcherWithAVerifiedAddress() {
        core.addMailRecipient(person(SECOND_ADMIN, null, "Без пошти", null, false, "ADMIN"));
        UUID job = UUID.randomUUID();
        handlers.handle(new GenerationCompleted(EventEnvelope.create(Clock.systemUTC()), job, GenerationStatus.SOLVED,
                0, -35, List.of(), List.of(new GenerationCompleted.ConstraintScore("Вікна в групи", 3, "0hard/-30soft"),
                new GenerationCompleted.ConstraintScore("Бонус", 9, "0hard/+9soft", "bonus", true, null)), null));
        mailWorker.drain();

        OutgoingMail letter = single(DISPATCHER);
        assertThat(mail.sent()).hasSize(1);
        assertThat(letter.subject()).isEqualTo("Розклад згенеровано: 0 жорстких порушень, штраф 35");
        assertThat(letter.text()).contains("• Вікна в групи — 3").doesNotContain("Бонус")
                .contains("http://localhost:5180/admin/generation?job=" + job);
        assertThat(letter.threadId()).isNull();
        assertThat(letter.unsubscribeUrl()).isEqualTo("http://localhost:5180/admin/notifications");
    }

    @Test
    void refusalsAndUnansweredRequestsReachTheDispatcher() {
        handlers.handle(request(CHANGE, CoreFixtures.TEACHER_NEPYIVODA, Instant.parse("2026-10-19T07:00:00Z")));
        handlers.handle(statusChanged(CHANGE, ChangeStatus.DECLINED, CoreFixtures.TEACHER_NEPYIVODA));
        mailWorker.drain();
        assertThat(single(DISPATCHER).subject()).isEqualTo("Відмова від заміни: Непийвода М.В., 2 пара 4КІ1, пн 19.10");

        UUID other = UUID.randomUUID();
        Instant deadline = Instant.parse("2026-10-19T06:30:00Z");
        handlers.handle(request(other, CoreFixtures.TEACHER_KRENTSIN, deadline));
        deliverMail();
        clock.set(Instant.parse("2026-10-19T06:31:00Z"));
        substitutions.remindOverdue();
        // core's own re-sent request with the same passed deadline is the same reminder round
        handlers.handle(request(other, CoreFixtures.TEACHER_KRENTSIN, deadline));
        deliverMail();

        assertThat(mail.sentTo(KRENTSIN)).extracting(OutgoingMail::subject).containsExactly(
                "Підтвердіть заміну до 09:30: пн, 19 жовтня, 2 пара, 4КІ1",
                "Нагадування: підтвердіть заміну — пн, 19 жовтня, 2 пара, 4КІ1");
        assertThat(mail.sentTo(DISPATCHER)).extracting(OutgoingMail::subject).containsExactly(
                "Відмова від заміни: Непийвода М.В., 2 пара 4КІ1, пн 19.10",
                "Немає відповіді на запит заміни: Кренцін М.Д., 2 пара 4КІ1, пн 19.10");
    }

    @Test
    void absenceLettersAreForThoseWhoSwitchedThemOn() {
        AbsenceReported absence = new AbsenceReported(EventEnvelope.create(Clock.systemUTC()), UUID.randomUUID(),
                CoreFixtures.TEACHER_KRENTSIN, "Кренцін М.Д.", Order35.DATE, Order35.DATE, List.of(1, 2),
                AbsenceSource.BOT);
        handlers.handle(absence);
        assertThat(intents.count()).as("off by default: not even an intent").isZero();

        preferences.update(ADMIN_ACCOUNT, true, Map.of(EmailKind.ABSENCE_REPORTED, true), null);
        handlers.handle(new AbsenceReported(EventEnvelope.create(Clock.systemUTC()), UUID.randomUUID(),
                CoreFixtures.TEACHER_KRENTSIN, "Кренцін М.Д.", Order35.DATE, Order35.DATE, null, AbsenceSource.WEB));
        mailWorker.drain();
        assertThat(single(DISPATCHER).subject()).isEqualTo("Відсутність: Кренцін М.Д., понеділок, 19 жовтня");
        assertThat(single(DISPATCHER).text()).contains("Пари: увесь день").contains("Джерело: кабінет викладача");
    }

    @Test
    void aTakenOverTelegramChatIsReportedByMail() {
        String code = issue(BindingTarget.teacher(CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В."));
        say(3003, "/start " + code);
        mailWorker.drain();

        OutgoingMail letter = single(NEPYIVODA);
        assertThat(letter.subject()).isEqualTo("Ваш Telegram у «Розклад ВТФК» підключено в іншому чаті");
        assertThat(letter.text()).contains("Якщо це зробили не ви");
    }

    @Test
    void orderRowsGoToTeachersWhoAskedForThem() {
        preferences.update(KRENTSIN_ACCOUNT, true, Map.of(EmailKind.ORDER_TEACHER, true), null);
        handlers.handle(new OrderPublished(EventEnvelope.create(Clock.systemUTC()), Order35.payload()));
        mailWorker.drain();

        assertThat(mail.sentTo(NEPYIVODA)).as("opt-in").isEmpty();
        OutgoingMail letter = single(KRENTSIN);
        assertThat(letter.subject()).isEqualTo("Розпорядження №35 на 19 жовтня: ваші зміни");
        assertThat(letter.text()).contains("4КІ1, 2 пара").contains("4КІ2, 4 пара")
                .contains("/api/documents/orders/" + Order35.ORDER_ID + ".pdf").doesNotContain("<b>", "<s>");
    }

    private void deliverMail() {
        mailWorker.drain();
        clock.advance(SETTLE);
        mailWorker.drain();
    }

    private OutgoingMail single(String address) {
        List<OutgoingMail> sent = mail.sentTo(address);
        assertThat(sent).hasSize(1);
        return sent.getFirst();
    }

    private static MailRecipient person(UUID account, UUID teacher, String name, String email, boolean google,
                                        String role) {
        return new MailRecipient(account, teacher, name, null, email, google, List.of(role), true, false, false);
    }

    private static SubstitutionConfirmationRequested notice(UUID changeId, UUID teacherId, String room) {
        return new SubstitutionConfirmationRequested(EventEnvelope.create(Clock.systemUTC()), changeId, teacherId,
                Order35.DATE, 2, LocalTime.of(9, 40), LocalTime.of(10, 40), "4КІ1", 0, DISCIPLINE, "Кренцін М.Д.",
                room, null, SubstitutionRequestMode.NOTIFY, null, null, null);
    }

    private static SubstitutionConfirmationRequested request(UUID changeId, UUID teacherId, Instant deadline) {
        return new SubstitutionConfirmationRequested(EventEnvelope.create(Clock.systemUTC()), changeId, teacherId,
                Order35.DATE, 2, LocalTime.of(9, 40), LocalTime.of(10, 40), "4КІ1", 0, DISCIPLINE, "Кренцін М.Д.",
                "6", deadline);
    }

    private static SubstitutionStatusChanged statusChanged(UUID changeId, ChangeStatus status, UUID teacherId) {
        return new SubstitutionStatusChanged(EventEnvelope.create(Clock.systemUTC()), changeId, status, teacherId,
                Order35.DATE, "4КІ1");
    }

    @SuppressWarnings("unused")
    private static LocalDate today() {
        return Order35.DATE;
    }
}
