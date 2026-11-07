package ua.vtc.rozklad.scheduler.solver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.Assignment;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.ConstraintScore;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;
import ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint;
import ua.vtc.rozklad.scheduler.support.Placements;
import ua.vtc.rozklad.scheduler.support.SyntheticCollege;

class TimetableResultsTest {

    private final SyntheticCollege.Problem college = SyntheticCollege.generate(SyntheticCollege.Options.tiny());
    private final TimetableProblemFactory factory = new TimetableProblemFactory(5);

    @Test
    void assignmentsFollowTheSnapshotOrderAndKeepRoomlessPinsWithoutRoom() {
        List<ProblemSnapshot.Unit> units = new ArrayList<>(college.snapshot().units());
        Assignment firstPlace = college.planted().getFirst();
        ProblemSnapshot.Unit first = units.getFirst();
        units.set(0, new ProblemSnapshot.Unit(first.unitId(), first.loadItemId(), first.groupId(), first.subgroup(),
                first.disciplineId(), first.teacherId(), first.weight(), first.requiredRoomType(),
                first.preferredRoomId(), first.homeRoomId(), new ProblemSnapshot.PinnedSlot(firstPlace.weekday(),
                firstPlace.pair(), firstPlace.parity(), null)));
        ProblemSnapshot snapshot = new ProblemSnapshot(college.snapshot().timeslots(),
                college.snapshot().shiftWindows(), college.snapshot().groups(), college.snapshot().rooms(),
                college.snapshot().teachers(), units);
        List<Assignment> planted = new ArrayList<>(college.planted());
        planted.set(0, new Assignment(firstPlace.unitId(), firstPlace.weekday(), firstPlace.pair(),
                firstPlace.parity(), null));

        List<Assignment> assignments = TimetableResults.assignments(
                Placements.apply(factory.create(snapshot, GenerationWeights.DEFAULT), planted));

        assertThat(assignments).containsExactlyElementsOf(planted);
        assertThat(assignments.getFirst().roomId()).isNull();
    }

    @Test
    void unplacedTimetableHasNoAssignments() {
        Timetable unplaced = factory.create(college.snapshot(), GenerationWeights.DEFAULT);

        assertThatThrownBy(() -> TimetableResults.assignments(unplaced)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void analysisListsEveryConstraintByItsUkrainianTitleHardFirst() {
        Timetable planted = Placements.apply(factory.create(college.snapshot(), GenerationWeights.DEFAULT),
                college.planted());

        List<ConstraintScore> analysis = TimetableResults.analysis(planted);

        assertThat(analysis).extracting(ConstraintScore::constraint)
                .containsExactlyElementsOf(Arrays.stream(TimetableConstraint.values())
                        .map(TimetableConstraint::title).toList());
        assertThat(analysis.subList(0, 7)).allSatisfy(row -> {
            assertThat(row.matchCount()).isZero();
            assertThat(row.score()).isEqualTo("0hard/0soft");
        });
    }
}
