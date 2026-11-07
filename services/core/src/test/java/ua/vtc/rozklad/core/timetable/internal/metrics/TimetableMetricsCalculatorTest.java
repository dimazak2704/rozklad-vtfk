package ua.vtc.rozklad.core.timetable.internal.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.D_ENG;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.D_FV;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.D_KM;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.D_NDEKSM;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.G_4KI1;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.G_4KI2;
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
        assertThat(calculator.calculate(List.of(), facts)).isEqualTo(MetricsDto.empty());
    }
}
