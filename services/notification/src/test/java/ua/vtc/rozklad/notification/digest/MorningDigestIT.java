package ua.vtc.rozklad.notification.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ua.vtc.rozklad.notification.support.CoreFixtures;
import ua.vtc.rozklad.notification.support.IntegrationTest;

/** 07:30 digest: subscribers with the digest on, study days only, once per day. */
class MorningDigestIT extends IntegrationTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);

    @Autowired
    private MorningDigest digest;

    @Test
    void subscribersGetTheDayOfTheirGroupOnce() {
        subscriber(3001, CoreFixtures.GROUP_4KI1, "4КІ1");
        subscriber(3002, CoreFixtures.GROUP_4KI1, "4КІ1");
        subscriber(3003, CoreFixtures.GROUP_4KI2, "4КІ2");
        subscriber(3004, CoreFixtures.GROUP_4KI1, "4КІ1");
        bindings.setDigest(3004, false);

        assertThat(digest.run(MONDAY)).isEqualTo(2);
        assertThat(digest.run(MONDAY)).isZero();
        worker.drain();

        assertThat(telegram.lastSentTo(3001).text())
                .startsWith("☀️ Доброго ранку! Розклад на сьогодні:\n\n👥 <b>4КІ1</b> · понеділок, 19 жовтня");
        assertThat(telegram.sentTo(3002)).hasSize(1);
        // у 4КІ2 сьогодні пар немає — нічого не надсилаємо
        assertThat(telegram.sentTo(3003)).isEmpty();
        assertThat(telegram.sentTo(3004)).isEmpty();
    }

    @Test
    void groupChatsGetTheMorningScheduleOnlyAfterSwitchingItOn() {
        groupChatOf(-1001, CoreFixtures.GROUP_4KI1, "4КІ1");
        groupChatOf(-1002, CoreFixtures.GROUP_4KI1, "4КІ1");
        bindings.setGroupChatDigest(-1002, true);
        subscriber(3001, CoreFixtures.GROUP_4KI1, "4КІ1");

        assertThat(digest.run(MONDAY)).isEqualTo(2);
        worker.drain();

        // новий чат групи ранкового розкладу не отримує, поки адміністратор не ввімкне /rozklad_on
        assertThat(telegram.sentTo(-1001)).isEmpty();
        assertThat(telegram.lastSentTo(-1002).text())
                .startsWith("☀️ Доброго ранку! Розклад на сьогодні:\n\n👥 <b>4КІ1</b> · понеділок, 19 жовтня");
        assertThat(telegram.sentTo(3001)).hasSize(1);
    }

    @Test
    void noDigestOnDaysOffOrWithoutCore() {
        subscriber(3001, CoreFixtures.GROUP_4KI1, "4КІ1");
        core.setToday(LocalDate.of(2026, 10, 24));
        assertThat(digest.run(LocalDate.of(2026, 10, 24))).isZero();

        core.setToday(MONDAY);
        core.setUnavailable(true);
        assertThat(digest.run(MONDAY)).isZero();
        assertThat(telegram.messageCount()).isZero();
    }
}
