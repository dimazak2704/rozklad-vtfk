package ua.vtc.rozklad.notification.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ua.vtc.rozklad.notification.binding.BindingTarget;
import ua.vtc.rozklad.notification.core.CoreQueries;
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

    @Autowired
    private CoreQueries queries;

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

    /** Admin chats bound by a code (not the owner chat): a dispatcher's private chat and the dispatchers' group. */
    @Test
    void adminChatsBoundByACodeSeeTheWholeArchiveToo() {
        bindings.bind(BindingTarget.adminChat(), privateChat(7104));
        subscriber(7104, CoreFixtures.GROUP_4KI1, "4КІ1");
        Sent today = today(7104);

        Edit september = open(7104, today, LocalDate.of(2026, 9, 7));

        assertThat(september.text()).startsWith("👥 <b>4КІ1</b> · понеділок, 7 вересня");
        assertThat(core.archiveCalls()).isPositive();

        bindings.bind(BindingTarget.adminChat(), groupChat(-7105, "Диспетчерська"));
        sayInGroup(-7105, "/today");
        int archived = core.archiveCalls();
        press(-7105, telegram.lastSentTo(-7105).messageId(), CallbackData.day(OwnerType.GROUP,
                CoreFixtures.GROUP_4KI1, LocalDate.of(2026, 9, 8)));

        assertThat(telegram.lastEditIn(-7105).text()).startsWith("👥 <b>4КІ1</b> · вівторок, 8 вересня");
        assertThat(core.archiveCalls()).isGreaterThan(archived);
    }

    /**
     * Wednesday 21.10: visitors see substitutions from 14.10 and schedules by whole weeks, so the bot opens Monday
     * 12.10 and Tuesday 13.10 to every chat (core and the site serve them too) and stops there.
     */
    @Test
    void midWeekBoundOpensTheWholeWeekOfIt() {
        clock.set(Instant.parse("2026-10-21T07:00:00Z"));
        core.setToday(LocalDate.of(2026, 10, 21));
        subscriber(STUDENT, CoreFixtures.GROUP_4KI1, "4КІ1");
        Sent today = today(STUDENT);
        assertThat(today.text()).startsWith("👥 <b>4КІ1</b> · середа, 21 жовтня");

        Edit monday = open(STUDENT, today, FIRST_DAY);
        assertThat(monday.text()).startsWith("👥 <b>4КІ1</b> · понеділок, 12 жовтня");
        assertThat(monday.buttons()).as("no ◀️ past the window").containsExactly("Вт 13.10 ▶️");
        Edit friday = open(STUDENT, today, FIRST_DAY.minusDays(3));
        assertThat(friday.text()).isEqualTo(BotTexts.ARCHIVED_DAY);
        assertThat(friday.buttons()).containsExactly("Пн 12.10 ▶️");
        assertThat(core.archiveCalls()).isZero();
    }

    /**
     * The current week needs no meta: a card whose schedule is cached still renders while core is down, with ◀️ on
     * Monday (pressing it checks the window again).
     */
    @Test
    void cachedMondayOfTheCurrentWeekRendersWithCoreDown() {
        subscriber(STUDENT, CoreFixtures.GROUP_4KI1, "4КІ1");
        today(STUDENT);
        queries.invalidateMeta(); // meta живе 30 с, а розклад дня — 10 хв
        core.setUnavailable(true);

        Sent today = today(STUDENT);

        assertThat(today.text()).startsWith("👥 <b>4КІ1</b> · понеділок, 19 жовтня");
        assertThat(today.buttons()).containsExactly("◀️ Пт 16.10", "Вт 20.10 ▶️");

        core.setUnavailable(false);
        Edit friday = open(STUDENT, today, LocalDate.of(2026, 10, 16));
        assertThat(friday.text()).startsWith("👥 <b>4КІ1</b> · п’ятниця, 16 жовтня");
    }

    /**
     * meta is cached for 30 s: when the window moved meanwhile, core's own refusal gives the archive card with the
     * button to the first day of the moved window, and the stale meta goes.
     */
    @Test
    void windowThatMovedMeanwhileStillGivesTheArchiveCard() {
        subscriber(STUDENT, CoreFixtures.GROUP_4KI1, "4КІ1");
        Sent today = today(STUDENT);
        core.setHistoryDays(0);

        Edit wednesday = open(STUDENT, today, LocalDate.of(2026, 10, 14));

        assertThat(wednesday.text()).isEqualTo(BotTexts.ARCHIVED_DAY);
        assertThat(wednesday.keyboard()).as("an edit without a keyboard drops the buttons").isNotNull();
        assertThat(wednesday.buttons()).containsExactly("Пн 19.10 ▶️");
        Edit monday = open(STUDENT, today, LocalDate.of(2026, 10, 19));
        assertThat(monday.buttons()).as("the next card knows the moved window").containsExactly("Вт 20.10 ▶️");
    }

    /** A staff chat whose cached window let a day go without the token asks again with it when core refuses. */
    @Test
    void staffChatAsksAgainWithTheServiceTokenWhenTheWindowMovedMeanwhile() {
        teacherChat(TEACHER, CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В.");
        Sent today = today(TEACHER);
        core.setHistoryDays(0);

        Edit wednesday = open(TEACHER, today, OwnerType.TEACHER, CoreFixtures.TEACHER_NEPYIVODA,
                LocalDate.of(2026, 10, 14));

        assertThat(wednesday.text()).isNotEqualTo(BotTexts.ARCHIVED_DAY).contains("14 жовтня");
        assertThat(wednesday.buttons()).containsExactly("◀️ Вт 13.10", "Чт 15.10 ▶️");
        assertThat(core.archiveCalls()).isPositive();
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
