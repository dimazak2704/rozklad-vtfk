package ua.vtc.rozklad.scheduler.solver;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.vtc.rozklad.contracts.Parity.BOTH;
import static ua.vtc.rozklad.contracts.Parity.DENOMINATOR;
import static ua.vtc.rozklad.contracts.Parity.NUMERATOR;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.Assignment;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;
import ua.vtc.rozklad.scheduler.solver.score.TimetableAnalyzer;
import ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint;
import ua.vtc.rozklad.scheduler.support.Placements;
import ua.vtc.rozklad.scheduler.support.TestSolvers;

/**
 * DOMAIN §5: two HALF units of different disciplines in one slot (A — numerator, B — denominator)
 * keep both weeks equal. The real 4КІ1 timetable has a numerator-only pair on Wednesday and no
 * numerator first pair on Thursday; one slot for both halves removes both broken days.
 */
class HalfPairingTest {

    private static final UUID GROUP = UUID.nameUUIDFromBytes("4КІ1".getBytes());
    private static final UUID ROOM = UUID.randomUUID();
    private static final int WHOLE_UNITS = 12;

    private final TimetableProblemFactory factory = new TimetableProblemFactory(5);

    @Test
    void puttingTheHalvesIntoOneSlotRemovesBothBrokenDays() {
        ProblemSnapshot snapshot = week4KI1();
        // як у реальному розкладі: середа — пара лише в чисельник, четвер — перша пара лише в знаменник
        List<Assignment> scattered = new ArrayList<>(wholeUnitsOnMondayToWednesday());
        scattered.add(new Assignment("half-A", 3, 3, NUMERATOR, ROOM));
        scattered.add(new Assignment("half-B", 4, 1, DENOMINATOR, ROOM));
        scattered.addAll(List.of(new Assignment("whole-10", 4, 2, BOTH, ROOM),
                new Assignment("whole-11", 4, 3, BOTH, ROOM)));
        List<Assignment> paired = new ArrayList<>(scattered);
        paired.set(paired.size() - 3, new Assignment("half-B", 3, 3, DENOMINATOR, ROOM));

        Timetable before = Placements.apply(factory.create(snapshot, GenerationWeights.DEFAULT), scattered);
        Timetable after = Placements.apply(factory.create(snapshot, GenerationWeights.DEFAULT), paired);

        assertThat(matches(before, TimetableConstraint.BROKEN_DAY)).isEqualTo(2);
        assertThat(matches(after, TimetableConstraint.BROKEN_DAY)).isZero();
        assertThat(matches(after, TimetableConstraint.HALF_PAIRING)).isEqualTo(1);
        HardSoftScore scoreBefore = TestSolvers.solutionManager().update(before);
        HardSoftScore scoreAfter = TestSolvers.solutionManager().update(after);
        assertThat(scoreAfter).isGreaterThan(scoreBefore);
    }

    @Test
    void solverPairsTheHalvesSoTheGroupHasNoBrokenDays() {
        Timetable solution = TestSolvers.solveTimed(TestSolvers.factory(
                        new TerminationConfig().withSpentLimit(Duration.ofSeconds(5))),
                factory.create(week4KI1(), GenerationWeights.DEFAULT)).solution();

        LessonUnit halfA = unit(solution, "half-A");
        LessonUnit halfB = unit(solution, "half-B");
        assertThat(halfA.getTimeslot()).isEqualTo(halfB.getTimeslot());
        assertThat(List.of(halfA.getParity(), halfB.getParity())).containsExactlyInAnyOrder(NUMERATOR, DENOMINATOR);
        assertThat(matches(solution, TimetableConstraint.BROKEN_DAY)).isZero();
        assertThat(solution.getScore().hardScore()).isZero();
    }

    private static long matches(Timetable timetable, TimetableConstraint constraint) {
        return TimetableAnalyzer.analyze(timetable).stream()
                .filter(result -> result.constraint() == constraint)
                .findFirst().orElseThrow().matchCount();
    }

    private static LessonUnit unit(Timetable timetable, String id) {
        return timetable.getUnits().stream().filter(unit -> unit.getId().equals(id)).findFirst().orElseThrow();
    }

    private static List<Assignment> wholeUnitsOnMondayToWednesday() {
        List<Assignment> result = new ArrayList<>();
        int[][] slots = {{1, 1}, {1, 2}, {1, 3}, {2, 1}, {2, 2}, {2, 3}, {3, 1}, {3, 2}, {5, 1}, {5, 2}};
        for (int i = 0; i < slots.length; i++) {
            result.add(new Assignment("whole-" + i, slots[i][0], slots[i][1], BOTH, ROOM));
        }
        return result;
    }

    /** One first-shift group: 12 weekly lessons and two halves, every lesson with its own teacher. */
    private static ProblemSnapshot week4KI1() {
        List<ProblemSnapshot.Timeslot> timeslots = new ArrayList<>();
        List<ProblemSnapshot.ShiftWindow> windows = new ArrayList<>();
        for (int weekday = 1; weekday <= 5; weekday++) {
            windows.add(new ProblemSnapshot.ShiftWindow(1, weekday, 1, 4));
            for (int pair = 1; pair <= 4; pair++) {
                timeslots.add(new ProblemSnapshot.Timeslot(weekday, pair));
            }
        }
        List<ProblemSnapshot.Teacher> teachers = new ArrayList<>();
        List<ProblemSnapshot.Unit> units = new ArrayList<>();
        for (int i = 0; i < WHOLE_UNITS + 2; i++) {
            boolean half = i >= WHOLE_UNITS;
            String id = half ? (i == WHOLE_UNITS ? "half-A" : "half-B") : "whole-" + i;
            UUID teacher = UUID.nameUUIDFromBytes(("teacher-" + i).getBytes());
            teachers.add(new ProblemSnapshot.Teacher(teacher, "Викладач " + i, List.of(), List.of()));
            units.add(new ProblemSnapshot.Unit(id, UUID.randomUUID(), GROUP, 0,
                    UUID.nameUUIDFromBytes(("discipline-" + i).getBytes()), teacher,
                    half ? UnitWeight.HALF : UnitWeight.WHOLE, null, null, ROOM, null));
        }
        return new ProblemSnapshot(timeslots, windows,
                List.of(new ProblemSnapshot.Group(GROUP, "4КІ1", 25, 1, List.of())),
                List.of(new ProblemSnapshot.Room(ROOM, "308", RoomType.CLASSROOM, 30, 1)), teachers, units);
    }
}
