package ua.vtc.rozklad.notification.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.notification.core.CoreDtos.ScheduleDay;
import ua.vtc.rozklad.notification.core.CoreQueries.DaySchedule;
import ua.vtc.rozklad.notification.core.OwnerType;
import ua.vtc.rozklad.notification.support.CoreFixtures;

class ScheduleCardsTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);

    @Test
    void groupDayShowsTimesTeachersRoomsAndTheSubstitution() {
        DaySchedule day = new DaySchedule(OwnerType.GROUP, CoreFixtures.GROUP_4KI1, "4КІ1",
                CoreFixtures.day4ki1(MONDAY));
        assertThat(ScheduleCards.day(day)).isEqualTo("""
                👥 <b>4КІ1</b> · понеділок, 19 жовтня
                <i>Знаменник</i>

                <b>1</b> · 08:30–09:30
                Комп. мережі · Колесник А.І. · ауд. 6

                <b>2</b> · 09:40–10:40 · 🔁 заміна
                НДКС · Непийвода М.В. · ауд. 6
                замість <s>СТП · Кренцін М.Д.</s>
                <i>Зв.зам.</i>

                <b>3</b> · 11:00–12:00 · ❌ пари не буде
                <s>Ін.мова (1 підгр.) · Крисак Л. · ауд. 110</s>

                ℹ️ Групи 4КІ навчаються дистанційно.""");
    }

    @Test
    void teacherSeesGroupsInsteadOfHimself() {
        DaySchedule day = new DaySchedule(OwnerType.TEACHER, CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В.",
                CoreFixtures.teacherDay(MONDAY));
        assertThat(ScheduleCards.day(day)).contains("🧑‍🏫 <b>Непийвода М.В.</b>")
                .contains("4КІ1 · НДКС · ауд. 6")
                .contains("замість <s>СТП</s>")
                .doesNotContain("Непийвода М.В. · ауд");
    }

    @Test
    void emptyDays() {
        ScheduleDay weekend = new ScheduleDay(LocalDate.of(2026, 10, 24), 6, null, null, false, null, List.of());
        assertThat(ScheduleCards.day(new DaySchedule(OwnerType.GROUP, CoreFixtures.GROUP_4KI1, "4КІ1", weekend)))
                .endsWith("🌿 Навчання немає");
        ScheduleDay free = new ScheduleDay(MONDAY, 1, "ПОНЕДІЛОК", Parity.DENOMINATOR, true, null, List.of());
        assertThat(ScheduleCards.day(new DaySchedule(OwnerType.ROOM, CoreFixtures.ROOM_6, "", free)))
                .startsWith("🚪 <b>Аудиторія</b>").endsWith("Пар немає 🙂");
        assertThat(ScheduleCards.summary(free)).isEqualTo("Пар немає");
        assertThat(ScheduleCards.summary(CoreFixtures.day4ki1(MONDAY))).isEqualTo("2 пари · змін: 2");
    }
}
