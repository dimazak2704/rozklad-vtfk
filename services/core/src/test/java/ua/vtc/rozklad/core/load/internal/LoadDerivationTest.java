package ua.vtc.rozklad.core.load.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.load.internal.LoadDerivation.Draft;
import ua.vtc.rozklad.core.load.internal.LoadDerivation.TemplateLessonRow;

/** TIMETABLE_DERIVED load (DOMAIN §12): BOTH = 1 pair a week, NUMERATOR / DENOMINATOR = ½. */
class LoadDerivationTest {

    private static final UUID GROUP = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID STP = UUID.fromString("00000000-0000-7000-8000-000000000011");
    private static final UUID ENGLISH = UUID.fromString("00000000-0000-7000-8000-000000000012");
    private static final UUID KRENTSIN = UUID.fromString("00000000-0000-7000-8000-000000000021");
    private static final UUID KRYSAK = UUID.fromString("00000000-0000-7000-8000-000000000022");
    private static final UUID MOZHAROVSKA = UUID.fromString("00000000-0000-7000-8000-000000000023");

    @Test
    void everyWeekLessonIsOnePairAndHalvesAreHalfAPair() {
        List<Draft> drafts = LoadDerivation.derive(List.of(
                lesson(STP, 0, KRENTSIN, Parity.BOTH),
                lesson(STP, 0, KRENTSIN, Parity.NUMERATOR)), 2);

        assertThat(drafts).singleElement().satisfies(draft -> {
            assertThat(draft.split()).isFalse();
            assertThat(draft.hoursPerWeek()).isEqualByComparingTo("3.0");
            assertThat(draft.teachers()).isEqualTo(Map.of(0, KRENTSIN));
            assertThat(draft.lessonKind()).isEqualTo(LessonKind.MIXED);
        });
    }

    @Test
    void subgroupLessonsBecomeASplitItemWithATeacherPerSubgroup() {
        List<Draft> drafts = LoadDerivation.derive(List.of(
                lesson(ENGLISH, 1, KRYSAK, Parity.BOTH),
                lesson(ENGLISH, 2, MOZHAROVSKA, Parity.BOTH),
                lesson(ENGLISH, 2, MOZHAROVSKA, Parity.DENOMINATOR)), 2);

        assertThat(drafts).singleElement().satisfies(draft -> {
            assertThat(draft.split()).isTrue();
            // кожна підгрупа отримує стільки пар, скільки найбільш завантажена: 1.5 пари = 3 год
            assertThat(draft.hoursPerWeek()).isEqualByComparingTo("3.0");
            assertThat(draft.teachers()).isEqualTo(Map.of(1, KRYSAK, 2, MOZHAROVSKA));
        });
    }

    @Test
    void wholeGroupAndSubgroupLessonsOfOneDisciplineGiveTwoItems() {
        List<Draft> drafts = LoadDerivation.derive(List.of(
                lesson(STP, 0, KRENTSIN, Parity.BOTH),
                lesson(STP, 1, KRENTSIN, Parity.NUMERATOR),
                lesson(STP, 2, KRENTSIN, Parity.DENOMINATOR)), 2);

        assertThat(drafts).extracting(Draft::split, draft -> draft.hoursPerWeek().toPlainString())
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(false, "2.0"),
                        org.assertj.core.groups.Tuple.tuple(true, "1.0"));
    }

    @Test
    void teacherWithMostPairsWinsAndMissingTeachersAreSkipped() {
        List<Draft> drafts = LoadDerivation.derive(List.of(
                lesson(STP, 0, KRYSAK, Parity.NUMERATOR),
                lesson(STP, 0, KRENTSIN, Parity.BOTH),
                lesson(STP, 0, null, Parity.DENOMINATOR)), 2);

        assertThat(drafts.getFirst().teachers()).isEqualTo(Map.of(0, KRENTSIN));
        assertThat(drafts.getFirst().hoursPerWeek()).isEqualByComparingTo("4.0");
    }

    @Test
    void lessonKindIsKeptWhenAllLessonsShareIt() {
        TemplateLessonRow lab = new TemplateLessonRow(GROUP, 0, STP, KRENTSIN, Parity.BOTH, LessonKind.LAB);

        assertThat(LoadDerivation.derive(List.of(lab, lab), 2).getFirst().lessonKind()).isEqualTo(LessonKind.LAB);
    }

    private static TemplateLessonRow lesson(UUID discipline, int subgroup, UUID teacher, Parity parity) {
        return new TemplateLessonRow(GROUP, subgroup, discipline, teacher, parity,
                parity == Parity.BOTH ? LessonKind.LECTURE : LessonKind.LAB);
    }
}
