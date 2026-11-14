package ua.vtc.rozklad.notification.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.notification.core.OwnerType;
import ua.vtc.rozklad.notification.support.CoreFixtures;
import ua.vtc.rozklad.notification.support.FakeTelegramClient.Edit;
import ua.vtc.rozklad.notification.support.FakeTelegramClient.Sent;
import ua.vtc.rozklad.notification.support.IntegrationTest;

/**
 * Core's public history window in the bot (owner decision 14.11.2026). Today is Monday 19.10.2026 and visitors see
 * a week back, so schedule cards open from Monday 12.10; staff chats (a teacher's own chat, admin chats) see older
 * days through the bot's service token.
 */
class HistoryWindowIT extends IntegrationTest {

    private static final long STUDENT = 7101;
    private static final long TEACHER = 7102;
    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 10, 12);

    @Test
    void visitorChatStopsAtTheFirstDayOfTheWindow() {
        subscriber(STUDENT, CoreFixtures.GROUP_4KI1, "4КІ1");
        Sent today = today(STUDENT);
        assertThat(today.buttons()).containsExactly("◀️ Пт 16.10", "Вт 20.10 ▶️");

        Edit tuesday = open(STUDENT, today, FIRST_DAY.plusDays(1));
        assertThat(tuesday.buttons()).containsExactly("◀️ Пн 12.10", "Ср 14.10 ▶️");
        Edit firstDay = open(STUDENT, today, FIRST_DAY);
        assertThat(firstDay.text()).startsWith("👥 <b>4КІ1</b> · понеділок, 12 жовтня");
        assertThat(firstDay.buttons()).as("no ◀️ past the window").containsExactly("Вт 13.10 ▶️");

        // стара кнопка або підроблений callback зі старішою датою: картка архіву, core не питали
        int asked = core.scheduleCalls();
        Edit friday = open(STUDENT, today, FIRST_DAY.minusDays(3));
        assertThat(friday.text()).isEqualTo(BotTexts.ARCHIVED_DAY);
        assertThat(friday.buttons()).containsExactly("Пн 12.10 ▶️");
        assertThat(core.scheduleCalls()).isEqualTo(asked);
        assertThat(core.archiveCalls()).isZero();
    }

    @Test
    void groupChatOfStudentsIsAVisitorToo() {
        groupChatOf(-7103, CoreFixtures.GROUP_4KI1, "4КІ1");
        sayInGroup(-7103, "/today");
        Sent today = telegram.lastSentTo(-7103);

        press(-7103, today.messageId(), CallbackData.day(OwnerType.GROUP, CoreFixtures.GROUP_4KI1,
                LocalDate.of(2026, 9, 7)));

        assertThat(telegram.lastEditIn(-7103).text()).isEqualTo(BotTexts.ARCHIVED_DAY);
    }

    @Test
    void teacherChatSeesTheWholeArchiveThroughTheServiceToken() {
        teacherChat(TEACHER, CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В.");
        Sent today = today(TEACHER);

        Edit firstDay = open(TEACHER, today, OwnerType.TEACHER, CoreFixtures.TEACHER_NEPYIVODA, FIRST_DAY);
        assertThat(firstDay.buttons()).containsExactly("◀️ Пт 09.10", "Вт 13.10 ▶️");
        assertThat(core.archiveCalls()).as("the window's days go without the token").isZero();

        Edit september = open(TEACHER, today, OwnerType.TEACHER, CoreFixtures.TEACHER_NEPYIVODA,
                LocalDate.of(2026, 9, 7));
        assertThat(september.text()).isNotEqualTo(BotTexts.ARCHIVED_DAY).contains("7 вересня");
        assertThat(september.buttons()).contains("◀️ Пт 04.09");
        assertThat(core.archiveCalls()).isPositive();
    }

    @Test
    void adminChatSeesTheWholeArchive() {
        owner();
        subscriber(OWNER_CHAT, CoreFixtures.GROUP_4KI1, "4КІ1");
        Sent today = today(OWNER_CHAT);

        Edit september = open(OWNER_CHAT, today, LocalDate.of(2026, 9, 7));

        assertThat(september.text()).startsWith("👥 <b>4КІ1</b> · понеділок, 7 вересня");
    }

    /** meta is cached for 30 s: when the window moved meanwhile, core's own refusal still gives the archive card. */
    @Test
    void windowThatMovedMeanwhileStillGivesTheArchiveCard() {
        subscriber(STUDENT, CoreFixtures.GROUP_4KI1, "4КІ1");
        Sent today = today(STUDENT);
        core.setHistoryDays(0);

        Edit wednesday = open(STUDENT, today, LocalDate.of(2026, 10, 14));

        assertThat(wednesday.text()).isEqualTo(BotTexts.ARCHIVED_DAY);
    }

    @Test
    void olderCoreWithoutTheWindowLimitsNothing() {
        core.setHistoryDays(null);
        subscriber(STUDENT, CoreFixtures.GROUP_4KI1, "4КІ1");
        Sent today = today(STUDENT);

        Edit september = open(STUDENT, today, LocalDate.of(2026, 9, 7));

        assertThat(september.text()).startsWith("👥 <b>4КІ1</b> · понеділок, 7 вересня");
        assertThat(core.archiveCalls()).isZero();
    }

    private Sent today(long chatId) {
        say(chatId, "📅 Сьогодні");
        return telegram.lastSentTo(chatId);
    }

    private Edit open(long chatId, Sent card, LocalDate date) {
        return open(chatId, card, OwnerType.GROUP, CoreFixtures.GROUP_4KI1, date);
    }

    private Edit open(long chatId, Sent card, OwnerType type, java.util.UUID ownerId, LocalDate date) {
        press(chatId, card.messageId(), CallbackData.day(type, ownerId, date));
        return telegram.lastEditIn(chatId);
    }
}
