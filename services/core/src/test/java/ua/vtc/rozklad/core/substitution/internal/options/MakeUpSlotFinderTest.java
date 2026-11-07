package ua.vtc.rozklad.core.substitution.internal.options;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.MakeUpSlot;

class MakeUpSlotFinderTest {

    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 20);

    @Test
    void takesThePairRightAfterTheGroupsLastPair() {
        var day = new MakeUpSlotFinder.Day(TUESDAY, 1, 4, Set.of(1, 2, 3), Set.of());

        assertThat(MakeUpSlotFinder.find(List.of(day), 3)).containsExactly(new MakeUpSlot(TUESDAY, 4));
    }

    @Test
    void fallsBackToThePairBeforeTheFirstOneWhenTheEndOfTheShiftIsReached() {
        var day = new MakeUpSlotFinder.Day(TUESDAY, 1, 4, Set.of(2, 3, 4), Set.of());

        assertThat(MakeUpSlotFinder.find(List.of(day), 3)).containsExactly(new MakeUpSlot(TUESDAY, 1));
    }

    @Test
    void skipsDaysWithoutLessonsOrWhenTheTeacherIsBusy() {
        var noLessons = new MakeUpSlotFinder.Day(TUESDAY, 1, 4, Set.of(), Set.of());
        var teacherBusy = new MakeUpSlotFinder.Day(TUESDAY.plusDays(1), 1, 4, Set.of(2, 3), Set.of(1, 4));
        var free = new MakeUpSlotFinder.Day(TUESDAY.plusDays(2), 5, 8, Set.of(5, 6), Set.of(8));

        assertThat(MakeUpSlotFinder.find(List.of(noLessons, teacherBusy, free), 3))
                .containsExactly(new MakeUpSlot(TUESDAY.plusDays(2), 7));
    }

    @Test
    void oneSlotPerDayEarliestFirstUpToTheLimit() {
        List<MakeUpSlotFinder.Day> days = List.of(
                new MakeUpSlotFinder.Day(TUESDAY.plusDays(3), 1, 4, Set.of(1), Set.of()),
                new MakeUpSlotFinder.Day(TUESDAY, 1, 4, Set.of(1), Set.of()),
                new MakeUpSlotFinder.Day(TUESDAY.plusDays(1), 1, 4, Set.of(1), Set.of()));

        assertThat(MakeUpSlotFinder.find(days, 2)).containsExactly(new MakeUpSlot(TUESDAY, 2),
                new MakeUpSlot(TUESDAY.plusDays(1), 2));
    }
}
