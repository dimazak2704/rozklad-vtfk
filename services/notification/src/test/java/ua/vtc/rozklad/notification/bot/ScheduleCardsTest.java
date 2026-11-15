package ua.vtc.rozklad.notification.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.notification.core.CoreDtos.GroupRef;
import ua.vtc.rozklad.notification.core.CoreDtos.PeriodKindRef;
import ua.vtc.rozklad.notification.core.CoreDtos.Practice;
import ua.vtc.rozklad.notification.core.CoreDtos.RoomRef;
import ua.vtc.rozklad.notification.core.CoreDtos.ScheduleDay;
import ua.vtc.rozklad.notification.core.CoreDtos.TeacherRef;
import ua.vtc.rozklad.notification.core.CoreQueries.DaySchedule;
import ua.vtc.rozklad.notification.core.OwnerType;
import ua.vtc.rozklad.notification.support.CoreFixtures;
import ua.vtc.rozklad.notification.text.TeacherLabels;

class ScheduleCardsTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);

    @Test
    void groupDayShowsTimesTeachersAsSurnameAndInitialsRoomsAndTheSubstitution() {
        DaySchedule day = new DaySchedule(OwnerType.GROUP, CoreFixtures.GROUP_4KI1, "4КІ1",
                CoreFixtures.day4ki1(MONDAY));
        assertThat(ScheduleCards.day(day)).isEqualTo("""
                👥 <b>4КІ1</b> · понеділок, 19 жовтня
                <i>Знаменник</i>

                <b>1</b> · 08:30–09:30
                Комп. мережі · Колесник А. І. · ауд. 6

                <b>2</b> · 09:40–10:40 · 🔁 заміна
                НДКС · Непийвода М. В. · ауд. 6
                замість <s>СТП · Кренцін М. Д.</s>
                <i>Взаємна заміна</i>

                <b>3</b> · 11:00–12:00 · ❌ пари не буде
                <s>Ін.мова (1 підгр.) · Крисак Л. · ауд. 110</s>

                ℹ️ Групи 4КІ навчаються дистанційно.""");
    }

    @Test
    void teacherSeesGroupsInsteadOfHimself() {
        DaySchedule day = new DaySchedule(OwnerType.TEACHER, CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В.",
                CoreFixtures.teacherDay(MONDAY));
        assertThat(ScheduleCards.day(day)).contains("🧑‍🏫 <b>Непийвода М. В.</b>")
                .contains("4КІ1 · НДКС · ауд. 6")
                .contains("замість <s>СТП</s>")
                .doesNotContain("Непийвода М. В. · ауд");
    }

    @Test
    void teachersWithoutInitialsInTheShortFormGetThemFromTheLabels() {
        // the lookup knows Непийвода by id; the replaced teacher in the fixture is plain text without an id
        TeacherLabels labels = (id, shortName) -> CoreFixtures.TEACHER_NEPYIVODA.equals(id)
                ? "Непийвода Максим В."
                : id == null ? shortName + "?" : TeacherLabels.SHORT_FORM.label(id, shortName);
        String card = ScheduleCards.day(new DaySchedule(OwnerType.GROUP, CoreFixtures.GROUP_4KI1, "4КІ1",
                CoreFixtures.day4ki1(MONDAY)), null, labels);
        assertThat(card).contains("НДКС · Непийвода Максим В. · ауд. 6", "Комп. мережі · Колесник А. І. · ауд. 6",
                "замість <s>СТП · Кренцін М.Д.?</s>");
        String teacherCard = ScheduleCards.day(new DaySchedule(OwnerType.TEACHER, CoreFixtures.TEACHER_NEPYIVODA,
                "Непийвода М.В.", CoreFixtures.teacherDay(MONDAY)), null, labels);
        assertThat(teacherCard).startsWith("🧑‍🏫 <b>Непийвода Максим В.</b>");
    }

    @Test
    void groupAtPracticeSeesThePracticeWithItsSupervisorsInsteadOfNoClasses() {
        Practice practice = new Practice(UUID.randomUUID(), "Електрорадіомонтажна практика",
                List.of(new TeacherRef(CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В.", "Непийвода"),
                        new TeacherRef(UUID.randomUUID(), "Кренцін М.Д.", "Кренцін")),
                List.of(new GroupRef(UUID.randomUUID(), "2КІ3", 2)), List.of());
        ScheduleDay atPractice = new ScheduleDay(MONDAY, 1, "ПОНЕДІЛОК", Parity.DENOMINATOR, false,
                "Електрорадіомонтажна практика; Осінній тиждень", List.of(), List.of(practice));
        String card = ScheduleCards.day(new DaySchedule(OwnerType.GROUP, UUID.randomUUID(), "2КІ3", atPractice),
                LocalDate.of(2026, 11, 2));

        assertThat(card).endsWith("""
                🛠 <b>Практика: Електрорадіомонтажна практика</b>
                Керівники: Непийвода М. В., Кренцін М. Д.
                Наступний навчальний день: <b>пн, 2 листопада</b>

                ℹ️ Осінній тиждень""").doesNotContain("Навчання немає");
        assertThat(ScheduleCards.summary(atPractice)).isEqualTo("Практика");

        Practice unassigned = new Practice(practice.periodId(), practice.title(), List.of(), practice.groups(),
                List.of());
        assertThat(ScheduleCards.day(new DaySchedule(OwnerType.GROUP, UUID.randomUUID(), "2КІ3",
                new ScheduleDay(MONDAY, 1, "ПОНЕДІЛОК", Parity.DENOMINATOR, false, practice.title(), List.of(),
                        List.of(unassigned)))))
                .endsWith("🛠 <b>Практика: Електрорадіомонтажна практика</b>");
    }

    @Test
    void supervisorSeesThePracticeHeLeadsNextToHisLessons() {
        Practice practice = new Practice(UUID.randomUUID(), "Технологічна практика", List.of(),
                List.of(new GroupRef(UUID.randomUUID(), "2КІ3", 2), new GroupRef(UUID.randomUUID(), "2КІ4", 2)),
                List.of());
        ScheduleDay withLessons = CoreFixtures.teacherDay(MONDAY);
        ScheduleDay supervised = new ScheduleDay(withLessons.date(), withLessons.weekday(), withLessons.weekdayLabel(),
                withLessons.parity(), true, null, withLessons.lessons(), List.of(practice));
        assertThat(ScheduleCards.day(new DaySchedule(OwnerType.TEACHER, CoreFixtures.TEACHER_NEPYIVODA,
                "Непийвода М.В.", supervised)))
                .contains("4КІ1 · НДКС · ауд. 6")
                .endsWith("🛠 <b>Керівництво практикою</b>: Технологічна практика, 2КІ3, 2КІ4");

        ScheduleDay onlyPractice = new ScheduleDay(MONDAY, 1, "ПОНЕДІЛОК", Parity.DENOMINATOR, true, null, List.of(),
                List.of(new Practice(UUID.randomUUID(), "Переддипломна практика", List.of(), List.of(), List.of(4))));
        assertThat(ScheduleCards.day(new DaySchedule(OwnerType.TEACHER, CoreFixtures.TEACHER_NEPYIVODA,
                "Непийвода М.В.", onlyPractice)))
                .endsWith("🛠 <b>Керівництво практикою</b>: Переддипломна практика, 4 курс")
                .doesNotContain("Пар немає");
        assertThat(ScheduleCards.summary(onlyPractice)).isEqualTo("Керівництво практикою");
    }

    @Test
    void groupOffItsPairsForAnotherKindSeesTheKindTitleAndRoom() {
        // НС (SUSPEND): «вид: назва, ауд. 401» жирним, керівник окремим рядком, назва не дублюється в примітці
        Practice ns = new Practice(UUID.randomUUID(), "Основи національного спротиву",
                List.of(new TeacherRef(CoreFixtures.TEACHER_NEPYIVODA, "Непийвода М.В.", "Непийвода")),
                List.of(new GroupRef(UUID.randomUUID(), "4КІ1", 4)), List.of(),
                new PeriodKindRef("НС", "Національний спротив", null, "SUSPEND"),
                new RoomRef(UUID.randomUUID(), "401", "CLASSROOM"));
        ScheduleDay day = new ScheduleDay(MONDAY, 1, "ПОНЕДІЛОК", Parity.DENOMINATOR, false,
                "Основи національного спротиву", List.of(), List.of(ns));
        String card = ScheduleCards.day(new DaySchedule(OwnerType.GROUP, CoreFixtures.GROUP_4KI1, "4КІ1", day));

        assertThat(card).endsWith("""
                🛠 <b>Національний спротив: Основи національного спротиву, ауд. 401</b>
                Керівник: Непийвода М. В.""").doesNotContain("Навчання немає", "ℹ️", "Практика");
        assertThat(ScheduleCards.summary(day)).isEqualTo("Національний спротив");

        // сесія без аудиторії й керівників
        Practice session = new Practice(UUID.randomUUID(), "Екзаменаційна сесія", List.of(),
                List.of(new GroupRef(UUID.randomUUID(), "4КІ1", 4)), List.of(),
                new PeriodKindRef("І", "Сесія", null, "SUSPEND"), null);
        assertThat(ScheduleCards.day(new DaySchedule(OwnerType.GROUP, CoreFixtures.GROUP_4KI1, "4КІ1",
                new ScheduleDay(MONDAY, 1, "ПОНЕДІЛОК", Parity.DENOMINATOR, false, null, List.of(),
                        List.of(session)))))
                .endsWith("🛠 <b>Сесія: Екзаменаційна сесія</b>");
    }

    @Test
    void practiceKindKeepsTodaysWordingAndShowsItsRoom() {
        Practice practice = new Practice(UUID.randomUUID(), "Електрорадіомонтажна практика", List.of(),
                List.of(new GroupRef(UUID.randomUUID(), "2КІ3", 2)), List.of(),
                new PeriodKindRef("НП", "Практика", null, "SUSPEND"), new RoomRef(UUID.randomUUID(), "204", null));
        ScheduleDay day = new ScheduleDay(MONDAY, 1, "ПОНЕДІЛОК", Parity.DENOMINATOR, false, null, List.of(),
                List.of(practice));
        assertThat(ScheduleCards.day(new DaySchedule(OwnerType.GROUP, UUID.randomUUID(), "2КІ3", day)))
                .endsWith("🛠 <b>Практика: Електрорадіомонтажна практика, ауд. 204</b>");
        assertThat(ScheduleCards.summary(day)).isEqualTo("Практика");

        ScheduleDay supervised = new ScheduleDay(MONDAY, 1, "ПОНЕДІЛОК", Parity.DENOMINATOR, true, null, List.of(),
                List.of(practice));
        assertThat(ScheduleCards.day(new DaySchedule(OwnerType.TEACHER, CoreFixtures.TEACHER_NEPYIVODA,
                "Непийвода М.В.", supervised)))
                .endsWith("🛠 <b>Керівництво практикою</b>: Електрорадіомонтажна практика, 2КІ3, ауд. 204");
        assertThat(ScheduleCards.summary(supervised)).isEqualTo("Керівництво практикою");
    }

    @Test
    void supervisorOfAnotherKindSeesKindNameAfterLeadership() {
        Practice ns = new Practice(UUID.randomUUID(), "Основи національного спротиву", List.of(),
                List.of(new GroupRef(UUID.randomUUID(), "4КІ1", 4), new GroupRef(UUID.randomUUID(), "4КІ2", 4)),
                List.of(), new PeriodKindRef("НС", "Національний спротив", null, "SUSPEND"),
                new RoomRef(UUID.randomUUID(), "401", "CLASSROOM"));
        ScheduleDay day = new ScheduleDay(MONDAY, 1, "ПОНЕДІЛОК", Parity.DENOMINATOR, true, null, List.of(),
                List.of(ns));
        String card = ScheduleCards.day(new DaySchedule(OwnerType.TEACHER, CoreFixtures.TEACHER_NEPYIVODA,
                "Непийвода М.В.", day));
        assertThat(card)
                .endsWith("🛠 <b>Керівництво</b>: Національний спротив: Основи національного спротиву, 4КІ1, 4КІ2, "
                        + "ауд. 401")
                .doesNotContain("Пар немає", "практик");
        assertThat(ScheduleCards.summary(day)).isEqualTo("Керівництво: Національний спротив");

        // курс замість груп, HTML у назві екранується
        Practice dpa = new Practice(UUID.randomUUID(), "ДПА <іспит>", List.of(), List.of(), List.of(4),
                new PeriodKindRef("Д", "ДПА", null, "SUSPEND"), null);
        assertThat(ScheduleCards.day(new DaySchedule(OwnerType.TEACHER, CoreFixtures.TEACHER_NEPYIVODA,
                "Непийвода М.В.", new ScheduleDay(MONDAY, 1, "ПОНЕДІЛОК", Parity.DENOMINATOR, true, null, List.of(),
                        List.of(dpa)))))
                .endsWith("🛠 <b>Керівництво</b>: ДПА: ДПА &lt;іспит&gt;, 4 курс");
    }

    /** The academic calendar import titles І, ЗУ, Д… with the kind name: the card does not print it twice. */
    @Test
    void titleThatOnlyRepeatsTheKindNameIsPrintedOnce() {
        Practice session = new Practice(UUID.randomUUID(), "Сесія", List.of(),
                List.of(new GroupRef(UUID.randomUUID(), "4КІ1", 4)), List.of(),
                new PeriodKindRef("І", "Сесія", null, "SUSPEND"), null);
        ScheduleDay day = new ScheduleDay(MONDAY, 1, "ПОНЕДІЛОК", Parity.DENOMINATOR, false, "Сесія", List.of(),
                List.of(session));
        assertThat(ScheduleCards.day(new DaySchedule(OwnerType.GROUP, CoreFixtures.GROUP_4KI1, "4КІ1", day)))
                .endsWith("🛠 <b>Сесія</b>").doesNotContain("Сесія: Сесія", "ℹ️");
        ScheduleDay supervised = new ScheduleDay(MONDAY, 1, "ПОНЕДІЛОК", Parity.DENOMINATOR, true, null, List.of(),
                List.of(session));
        assertThat(ScheduleCards.day(new DaySchedule(OwnerType.TEACHER, CoreFixtures.TEACHER_NEPYIVODA,
                "Непийвода М.В.", supervised)))
                .endsWith("🛠 <b>Керівництво</b>: Сесія, 4КІ1");

        // регістр і пробіли не важать; аудиторія лишається
        Practice meeting = new Practice(UUID.randomUUID(), " збори ", List.of(), List.of(), List.of(1, 2),
                new PeriodKindRef("ЗУ", "Збори", null, "SUSPEND"), new RoomRef(UUID.randomUUID(), "401", "CLASSROOM"));
        assertThat(ScheduleCards.day(new DaySchedule(OwnerType.TEACHER, CoreFixtures.TEACHER_NEPYIVODA,
                "Непийвода М.В.", new ScheduleDay(MONDAY, 1, "ПОНЕДІЛОК", Parity.DENOMINATOR, true, null, List.of(),
                        List.of(meeting)))))
                .endsWith("🛠 <b>Керівництво</b>: Збори, 1, 2 курси, ауд. 401");
    }

    /** Two kinds on one day: the summary does not pick the first one, as on the site. */
    @Test
    void summaryOfSeveralKindsOnOneDayNamesNone() {
        Practice ns = new Practice(UUID.randomUUID(), "Основи національного спротиву", List.of(),
                List.of(new GroupRef(UUID.randomUUID(), "4КІ1", 4)), List.of(),
                new PeriodKindRef("НС", "Національний спротив", null, "SUSPEND"), null);
        Practice meeting = new Practice(UUID.randomUUID(), "Збори", List.of(),
                List.of(new GroupRef(UUID.randomUUID(), "4КІ1", 4)), List.of(),
                new PeriodKindRef("ЗУ", "Збори", null, "SUSPEND"), null);
        assertThat(ScheduleCards.summary(new ScheduleDay(MONDAY, 1, "ПОНЕДІЛОК", Parity.DENOMINATOR, false, null,
                List.of(), List.of(ns, meeting)))).isEqualTo("Без пар");
        assertThat(ScheduleCards.summary(new ScheduleDay(MONDAY, 1, "ПОНЕДІЛОК", Parity.DENOMINATOR, true, null,
                List.of(), List.of(ns, meeting)))).isEqualTo("Керівництво");
        assertThat(ScheduleCards.summary(new ScheduleDay(MONDAY, 1, "ПОНЕДІЛОК", Parity.DENOMINATOR, false, null,
                List.of(), List.of(ns, ns)))).isEqualTo("Національний спротив");
    }

    @Test
    void kindWithoutNameAndRoomWithoutNameFallBackToPracticeWording() {
        Practice blank = new Practice(UUID.randomUUID(), "Технологічна практика", List.of(),
                List.of(new GroupRef(UUID.randomUUID(), "2КІ3", 2)), List.of(),
                new PeriodKindRef("Т", " ", null, "SUSPEND"), new RoomRef(UUID.randomUUID(), null, null));
        ScheduleDay day = new ScheduleDay(MONDAY, 1, "ПОНЕДІЛОК", Parity.DENOMINATOR, false, null, List.of(),
                List.of(blank));
        assertThat(ScheduleCards.day(new DaySchedule(OwnerType.GROUP, UUID.randomUUID(), "2КІ3", day)))
                .endsWith("🛠 <b>Практика: Технологічна практика</b>");
        assertThat(ScheduleCards.summary(day)).isEqualTo("Практика");
        // розклад аудиторії не показує блок періодів
        assertThat(ScheduleCards.day(new DaySchedule(OwnerType.ROOM, CoreFixtures.ROOM_6, "6", day)))
                .doesNotContain("🛠");
    }

    @Test
    void emptyDays() {
        ScheduleDay weekend = new ScheduleDay(LocalDate.of(2026, 10, 24), 6, null, null, false, null, List.of());
        assertThat(ScheduleCards.day(new DaySchedule(OwnerType.GROUP, CoreFixtures.GROUP_4KI1, "4КІ1", weekend)))
                .endsWith("🌿 Навчання немає");
        assertThat(ScheduleCards.day(new DaySchedule(OwnerType.GROUP, CoreFixtures.GROUP_4KI1, "4КІ1", weekend),
                LocalDate.of(2026, 10, 26)))
                .endsWith("🌿 Навчання немає\nНаступний навчальний день: <b>пн, 26 жовтня</b>");
        ScheduleDay free = new ScheduleDay(MONDAY, 1, "ПОНЕДІЛОК", Parity.DENOMINATOR, true, null, List.of());
        assertThat(ScheduleCards.day(new DaySchedule(OwnerType.ROOM, CoreFixtures.ROOM_6, "", free)))
                .startsWith("🚪 <b>Аудиторія</b>").endsWith("Пар немає 🙂");
        assertThat(ScheduleCards.day(new DaySchedule(OwnerType.ROOM, CoreFixtures.ROOM_6, "", free),
                LocalDate.of(2026, 10, 20))).as("a study day without lessons needs no hint")
                .doesNotContain("Наступний");
        assertThat(ScheduleCards.summary(free)).isEqualTo("Пар немає");
        assertThat(ScheduleCards.summary(CoreFixtures.day4ki1(MONDAY))).isEqualTo("2 пари · змін: 2");
    }
}
