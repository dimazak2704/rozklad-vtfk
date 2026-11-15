package ua.vtc.rozklad.scheduler.solver;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;
import ua.vtc.rozklad.scheduler.solver.score.TimetableAnalyzer;
import ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint;
import ua.vtc.rozklad.scheduler.support.TestSolvers;

/**
 * genfix repair: keeping PE in the sports hall is soft. Two groups had PE in the hall in the base version, but the
 * hall takes one group at a time and both groups have the same single slot (a new group, the hall closed for a
 * pair). With the old hard narrowing of the range the run could only end at −2 hard (room conflict in both weeks);
 * now one PE lesson goes to a classroom at the {@code specialRooms} cost, and at weight 0 at no cost.
 */
class SpecialRoomsSolverTest {

    private static final UUID FIRST = UUID.randomUUID();
    private static final UUID SECOND = UUID.randomUUID();
    private static final UUID HALL = UUID.randomUUID();
    private static final UUID ROOM_304 = UUID.randomUUID();
    private static final UUID PE = UUID.randomUUID();
    private static final UUID TEACHER_A = UUID.randomUUID();
    private static final UUID TEACHER_B = UUID.randomUUID();

    private final TimetableProblemFactory factory = new TimetableProblemFactory(5);

    @Test
    void hallOverCapacitySendsOnePeLessonToAClassroomInsteadOfStayingInfeasible() {
        Timetable solution = solve(GenerationWeights.DEFAULT);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(solution.getUnits()).extracting(unit -> unit.getRoom().getType())
                .containsExactlyInAnyOrder(RoomType.GYM, RoomType.CLASSROOM);
        TimetableAnalyzer.ConstraintResult lost = result(solution);
        assertThat(lost.matchCount()).isEqualTo(1);
        assertThat(lost.score().softScore()).isEqualTo(-2L * GenerationWeights.DEFAULT_SPECIAL_ROOMS);
    }

    @Test
    void weightZeroLetsPeUseAClassroomForFree() {
        GenerationWeights off = new GenerationWeights(30, 10, 9, 5, 1, 10, 3, 1, 10, 10, 5, 3, 10, 0);

        Timetable solution = solve(off);

        assertThat(solution.getScore().hardScore()).isZero();
        assertThat(result(solution).score().softScore()).isZero();
    }

    private Timetable solve(GenerationWeights weights) {
        Timetable problem = factory.create(snapshot(), weights);
        assertThat(problem.getUnits()).allSatisfy(unit -> {
            assertThat(unit.getBaseRoomTypes()).containsExactly(RoomType.GYM);
            assertThat(unit.getRoomRange()).extracting(room -> room.getType())
                    .as("the hall first, the classroom still in the range")
                    .containsExactly(RoomType.GYM, RoomType.CLASSROOM);
        });
        return TestSolvers.solveTimed(TestSolvers.factory(new TerminationConfig()
                .withSpentLimit(Duration.ofSeconds(20))
                .withUnimprovedSpentLimit(Duration.ofSeconds(3))), problem).solution();
    }

    private static TimetableAnalyzer.ConstraintResult result(Timetable solution) {
        return TimetableAnalyzer.analyze(solution).stream()
                .filter(row -> row.constraint() == TimetableConstraint.SPECIAL_ROOM_LOST)
                .findFirst().orElseThrow();
    }

    private static ProblemSnapshot snapshot() {
        ProblemSnapshot.PinnedSlot inTheHall = new ProblemSnapshot.PinnedSlot(1, 1, Parity.BOTH, HALL);
        return new ProblemSnapshot(
                List.of(new ProblemSnapshot.Timeslot(1, 1)),
                List.of(new ProblemSnapshot.ShiftWindow(1, 1, 1, 1)),
                List.of(new ProblemSnapshot.Group(FIRST, "1КІ1", 25, 1, List.of()),
                        new ProblemSnapshot.Group(SECOND, "1КІ2", 25, 1, List.of())),
                List.of(new ProblemSnapshot.Room(HALL, "с.з.", RoomType.GYM, 90, 1),
                        new ProblemSnapshot.Room(ROOM_304, "304", RoomType.CLASSROOM, 30, 1)),
                List.of(new ProblemSnapshot.Teacher(TEACHER_A, "Крисак Л.", List.of(), List.of()),
                        new ProblemSnapshot.Teacher(TEACHER_B, "Шидловський В.", List.of(), List.of())),
                List.of(unit("pe1", FIRST, TEACHER_A, inTheHall), unit("pe2", SECOND, TEACHER_B, inTheHall)));
    }

    private static ProblemSnapshot.Unit unit(String id, UUID group, UUID teacher, ProblemSnapshot.PinnedSlot initial) {
        return new ProblemSnapshot.Unit(id, UUID.randomUUID(), group, 0, PE, teacher, UnitWeight.WHOLE, null, null,
                null, null, initial, HALL, false, null);
    }
}
