package ua.vtc.rozklad.core.timetable.internal.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.D_ENG;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.D_FV;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.D_KM;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.D_NDEKSM;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.G_2KI1;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.G_4KI1;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.G_4KI2;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.T_KRENTSIN;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.T_NEPYIVODA;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.at;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.facts;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.timetable.internal.conflict.ConflictEngine;
import ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFacts;
import ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures;
import ua.vtc.rozklad.core.timetable.internal.conflict.SlotLesson;

/**
 * Metrics on a 4КІ1-like week (DOMAIN.md §5): a numerator-only pair on Wednesday and no numerator
 * first pair on Thursday give two broken days; putting both halves into one slot removes both.
 */
class TimetableMetricsCalculatorTest {

    /** A discipline that exists only as a half (Захист України in the real 4КІ1 week). */
    private static final UUID D_ZU = ConflictFixtures.id("discipline-zu");

    private final TimetableMetricsCalculator calculator = new TimetableMetricsCalculator();
    private final ConflictFacts facts = facts().build();

    private List<SlotLesson> week4ki1(SlotLesson wednesdayHalf) {
        List<SlotLesson> lessons = new ArrayList<>(List.of(
                at(1, 1).discipline(D_KM).teacher(T_NEPYIVODA).build(),
                at(1, 2).build(),
                at(1, 3).discipline(D_NDEKSM).build(),
                at(1, 4).discipline(D_FV).build(),
                at(3, 1).discipline(D_KM).build(),
                at(3, 2).discipline(D_NDEKSM).build(),
                wednesdayHalf,
                at(4, 1).parity(Parity.DENOMINATOR).discipline(D_ENG).build(),
                at(4, 2).build(),
                at(4, 3).discipline(D_NDEKSM).build(),
                // 4КІ2: вікно на 3 парі в понеділок; Непийвода веде 1 пару в 4КІ1 і 4 пару в 4КІ2
                at(1, 2).group(G_4KI2).build(),
                at(1, 4).group(G_4KI2).discipline(D_KM).teacher(T_NEPYIVODA).build()));
        return lessons;
    }

    @Test
    void halvesInDifferentSlotsGiveTwoBrokenDays() {
        MetricsDto metrics = calculator.calculate(
                week4ki1(at(3, 3).parity(Parity.NUMERATOR).discipline(D_ZU).build()), facts);

        assertThat(metrics.brokenDays()).isEqualTo(2);
        assertThat(metrics.groupWindows()).isEqualTo(2);
        assertThat(metrics.teacherWindows()).isEqualTo(4);
        // остання пара вікна 1 зміни (4-та) у понеділок: 4КІ1 і 4КІ2, чисельник і знаменник
        assertThat(metrics.latePairs()).isEqualTo(4);
        assertThat(metrics.maxPairsPerDay()).isEqualTo(4);
        assertThat(metrics.perGroup()).extracting(row -> row.group().code()).containsExactly("4КІ1", "4КІ2");
        assertThat(metrics.perGroup().getFirst()).satisfies(row -> {
            assertThat(row.brokenDays()).isEqualTo(2);
            assertThat(row.windows()).isZero();
            assertThat(row.latePairs()).isEqualTo(2);
            assertThat(row.maxPairsPerDay()).isEqualTo(4);
        });
        assertThat(metrics.perGroup().get(1).windows()).isEqualTo(2);
    }

    @Test
    void pairingTheTwoHalvesInOneSlotRemovesBothBrokenDays() {
        List<SlotLesson> before = week4ki1(at(3, 3).parity(Parity.NUMERATOR).discipline(D_ZU).build());
        List<SlotLesson> after = week4ki1(at(4, 1).parity(Parity.NUMERATOR).discipline(D_ZU).build());

        assertThat(calculator.calculate(before, facts).brokenDays()).isEqualTo(2);
        assertThat(calculator.calculate(after, facts).brokenDays()).isZero();
        assertThat(new ConflictEngine().check(after, facts).soft())
                .noneMatch(conflict -> conflict.kind() == ConflictKind.BROKEN_DAY);
        assertThat(new ConflictEngine().check(after, facts).hard()).isEmpty();
    }

    @Test
    void emptyTimetableHasZeroMetrics() {
        assertThat(calculator.calculate(List.of(), facts).withHardConflicts(0)).isEqualTo(MetricsDto.empty());
        assertThat(calculator.calculate(List.of(), facts).hardConflicts())
                .as("filled in by the caller that ran the conflict engine").isNull();
    }

    /**
     * B5: one definition of «4-та пара зміни» (contracts.LatePairs): shift 1 the 4th pair, shift 2 the 8th;
     * Friday shift 2 (pairs 4–6) has none, although p6 is the last pair of its window.
     */
    @Test
    void latePairIsTheFourthPairOfTheShiftWindow() {
        List<SlotLesson> lessons = List.of(
                at(1, 4).build(),
                at(2, 1).build(), at(2, 2).build(), at(2, 3).build(),
                at(1, 5).group(G_2KI1).build(), at(1, 8).group(G_2KI1).build(),
                at(5, 4).group(G_2KI1).build(), at(5, 5).group(G_2KI1).build(), at(5, 6).group(G_2KI1).build());

        MetricsDto metrics = calculator.calculate(lessons, facts);

        assertThat(metrics.perGroup()).extracting(row -> row.group().code(), MetricsDto.GroupMetrics::latePairs)
                .containsExactly(tuple("2КІ1", 2), tuple("4КІ1", 2));
        assertThat(metrics.latePairs()).isEqualTo(4);
    }

    /**
     * M1: a teacher's wait between a 1st-shift and a 2nd-shift lesson is not a window; a gap inside one
     * shift still is. Counted per kind of week.
     */
    @Test
    void teacherWaitBetweenShiftsIsCountedApartFromWindows() {
        List<SlotLesson> lessons = List.of(
                at(2, 1).teacher(T_KRENTSIN).build(),
                at(2, 3).teacher(T_KRENTSIN).build(),
                at(2, 6).group(G_2KI1).teacher(T_KRENTSIN).build());

        MetricsDto metrics = calculator.calculate(lessons, facts);

        assertThat(metrics.teacherWindows()).as("pair 2 inside shift 1").isEqualTo(2);
        assertThat(metrics.teacherShiftWaits()).as("pairs 4–5 between the shifts").isEqualTo(4);
        assertThat(new ConflictEngine().check(lessons, facts).soft())
                .filteredOn(conflict -> conflict.message().startsWith("Вікно у викладача"))
                .singleElement()
                .satisfies(conflict -> assertThat(conflict.message()).contains("між 1 і 3 парою"));
    }

    /**
     * B4: the catalog stores no subgroups of 4КІ1, but its subgroups study on Tuesday, so on Monday subgroup 2
     * sits through pairs 1 and 3 with a window at 2 while subgroup 1 has English. The lone subgroup pairs are
     * counted too (M3 metric).
     */
    @Test
    void subgroupsAreTheSameAllWeekSoAMondayWindowOfSubgroupTwoCounts() {
        List<SlotLesson> lessons = List.of(
                at(1, 1).build(),
                at(1, 2).subgroup(1).discipline(D_ENG).build(),
                at(1, 3).build(),
                at(2, 1).subgroup(2).discipline(D_ENG).build(),
                at(2, 2).build());

        MetricsDto metrics = calculator.calculate(lessons, facts);

        assertThat(metrics.groupWindows()).isEqualTo(2);
        assertThat(metrics.loneSubgroupSlots()).as("Mon p2 and Tue p1, both weeks").isEqualTo(4);
        assertThat(metrics.perGroup().getFirst().loneSubgroupSlots()).isEqualTo(4);
        assertThat(new ConflictEngine().check(lessons, facts).soft())
                .filteredOn(conflict -> conflict.kind() == ConflictKind.WINDOW)
                .singleElement()
                .satisfies(conflict -> assertThat(conflict.message()).contains("4КІ1").contains("між 1 і 3 парою"));
    }

    @Test
    void parallelSubgroupsAreNotLone() {
        List<SlotLesson> lessons = List.of(
                at(1, 1).subgroup(1).discipline(D_ENG).build(),
                at(1, 1).subgroup(2).discipline(D_ENG).teacher(T_KRENTSIN).build(),
                at(1, 2).build());

        assertThat(calculator.calculate(lessons, facts).loneSubgroupSlots()).isZero();
    }

    /** M4: one-pair days of groups and teachers, teacher days and days above the teacher's daily limit. */
    @Test
    void dailyBalanceOfGroupsAndTeachers() {
        List<SlotLesson> lessons = new ArrayList<>();
        for (int pair = 1; pair <= 4; pair++) {
            lessons.add(at(1, pair).teacher(T_NEPYIVODA).build());
        }
        lessons.add(at(1, 1).group(G_4KI2).teacher(T_KRENTSIN).build());
        lessons.add(at(1, 2).group(G_4KI2).build());
        lessons.add(at(2, 3).group(G_4KI2).teacher(T_NEPYIVODA).build());
        lessons.add(at(3, 1).parity(Parity.NUMERATOR).group(G_4KI2).build());

        MetricsDto metrics = calculator.calculate(lessons, facts);
        MetricsDto strict = calculator.calculate(lessons, facts, 3);

        // 4КІ2: вівторок (обидва тижні) і середа (лише чисельник) мають по одній парі
        assertThat(metrics.studentOnePairDays()).isEqualTo(3);
        assertThat(metrics.perGroup()).extracting(MetricsDto.GroupMetrics::onePairDays).containsExactly(0, 3);
        // Непийвода: понеділок 4 пари і вівторок 1 пара; Кренцін: понеділок 1 пара — по два тижні
        assertThat(metrics.teacherDays()).isEqualTo(6);
        assertThat(metrics.teacherOnePairDays()).isEqualTo(4);
        assertThat(metrics.teacherOverloadDays()).as("4 pairs is within the default limit").isZero();
        assertThat(strict.teacherOverloadDays()).isEqualTo(2);
    }
}
