package ua.vtc.rozklad.scheduler.support;

import static org.assertj.core.api.Assertions.assertThat;

import ai.timefold.solver.core.api.score.HardSoftScore;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.Assignment;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.scheduler.solver.TimetableProblemFactory;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;
import ua.vtc.rozklad.scheduler.solver.score.TimetableAnalyzer;

/** The synthetic shapes are feasible and have the features they are meant to stress. */
class ShapedCollegeTest {

    static List<ShapedCollege.Shape> shapes() {
        return ShapedCollege.Shape.all();
    }

    @ParameterizedTest
    @MethodSource("shapes")
    void plantedTimetableHasNoHardViolation(ShapedCollege.Shape shape) {
        ShapedCollege.Problem college = ShapedCollege.generate(shape);
        TimetableProblemFactory factory = new TimetableProblemFactory(5);

        Timetable planted = college.place(factory.create(college.snapshot(), GenerationWeights.DEFAULT));
        HardSoftScore score = TimetableAnalyzer.totalScore(TimetableAnalyzer.analyze(planted));

        assertThat(score.hardScore()).isZero();
        assertThat(college.snapshot().groups()).hasSize(shape.shiftOneGroups() + shape.shiftTwoGroups());
        assertThat(factory.create(college.withInitial(), GenerationWeights.DEFAULT).getUnits())
                .allSatisfy(unit -> assertThat(unit.isPinned() || unit.hasBase()).isTrue());
    }

    @ParameterizedTest
    @MethodSource("shapes")
    void groupsHaveTheShapesNumberOfPairsAWeek(ShapedCollege.Shape shape) {
        ShapedCollege.Problem college = ShapedCollege.generate(shape);

        Map<UUID, Integer> pairs = pairsPerWeek(college);

        for (ProblemSnapshot.Group group : college.snapshot().groups()) {
            int min = group.shift() == 1 ? shape.minPairs() : shape.minPairsShiftTwo();
            int max = group.shift() == 1 ? shape.maxPairs() : shape.maxPairsShiftTwo();
            assertThat(pairs.get(group.id())).as(group.code()).isBetween(min, max);
        }
    }

    @Test
    void realShapedCollegeIsAsBigAsTheRealOne() {
        ShapedCollege.Problem college = ShapedCollege.generate(ShapedCollege.Shape.realShaped());

        Map<UUID, Integer> pairs = pairsPerWeek(college);

        assertThat(college.snapshot().groups()).hasSize(56);
        assertThat(pairs.values()).contains(14, 19);
        assertThat(college.snapshot().units()).hasSizeBetween(950, 1250);
        assertThat(college.snapshot().units()).filteredOn(unit -> unit.siblingKey() != null).isNotEmpty();
    }

    @Test
    void tightSecondShiftFillsAtLeast18Of19Slots() {
        ShapedCollege.Problem college = ShapedCollege.generate(ShapedCollege.Shape.tightShiftTwo());

        Map<UUID, Integer> pairs = pairsPerWeek(college);

        assertThat(college.snapshot().groups()).filteredOn(group -> group.shift() == 2).hasSize(12)
                .allSatisfy(group -> assertThat(pairs.get(group.id())).isBetween(18, 19))
                .anySatisfy(group -> assertThat(pairs.get(group.id())).isEqualTo(19));
        assertThat(college.snapshot().rooms()).filteredOn(room -> room.type() == RoomType.GYM)
                .singleElement().extracting(ProblemSnapshot.Room::maxParallel).isEqualTo(2);
    }

    @Test
    void manySplitsHasParallelAndCrossedSubgroupsWithSiblingKeys() {
        ShapedCollege.Problem college = ShapedCollege.generate(ShapedCollege.Shape.manySplits());
        Map<String, Assignment> placed = college.planted().stream()
                .collect(Collectors.toMap(Assignment::unitId, assignment -> assignment));

        Map<String, List<ProblemSnapshot.Unit>> siblings = college.snapshot().units().stream()
                .filter(unit -> unit.siblingKey() != null)
                .collect(Collectors.groupingBy(ProblemSnapshot.Unit::siblingKey));

        assertThat(college.snapshot().groups()).allSatisfy(group -> assertThat(group.subgroups()).containsExactly(1, 2));
        assertThat(siblings.values()).allSatisfy(copies -> assertThat(copies).hasSize(2)
                .extracting(ProblemSnapshot.Unit::subgroup).containsExactlyInAnyOrder(1, 2));
        // паралельні: різні викладачі в одному слоті; схрещені: один викладач на сусідніх парах
        assertThat(siblings.values()).anySatisfy(copies -> {
            assertThat(copies.get(0).teacherId()).isNotEqualTo(copies.get(1).teacherId());
            assertThat(slot(placed, copies.get(0))).isEqualTo(slot(placed, copies.get(1)));
        });
        assertThat(siblings.values()).anySatisfy(copies -> {
            assertThat(copies.get(0).teacherId()).isEqualTo(copies.get(1).teacherId());
            assertThat(Math.abs(placed.get(copies.get(0).unitId()).pair() - placed.get(copies.get(1).unitId()).pair()))
                    .isEqualTo(1);
        });
        assertThat(college.snapshot().units()).anySatisfy(unit ->
                assertThat(unit.requiredRoomType()).isEqualTo(RoomType.COMPUTER_LAB));
    }

    @Test
    void teacherLimitsHasUnavailableAndUnwantedSlotsAndPartTimeTeachers() {
        ShapedCollege.Problem college = ShapedCollege.generate(ShapedCollege.Shape.teacherLimits());
        Set<UUID> teaching = college.snapshot().units().stream().map(ProblemSnapshot.Unit::teacherId)
                .collect(Collectors.toSet());

        List<ProblemSnapshot.Teacher> partTime = college.snapshot().teachers().stream()
                .filter(teacher -> weekdays(teacher.unavailable()).size() == 3 && teacher.unavailable().size() >= 3 * 6)
                .toList();

        assertThat(partTime).hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(teacher -> assertThat(teaching).contains(teacher.id()));
        assertThat(college.snapshot().teachers()).filteredOn(teacher -> !teacher.unavailable().isEmpty())
                .hasSizeGreaterThan(college.snapshot().teachers().size() / 4);
        assertThat(college.snapshot().teachers()).filteredOn(teacher -> !teacher.unwanted().isEmpty())
                .hasSizeGreaterThan(college.snapshot().teachers().size() / 3);
        assertThat(college.snapshot().teachers()).allSatisfy(teacher ->
                assertThat(teacher.maxPairsPerDay()).isEqualTo(4));
    }

    @Test
    void smallStreamHasPinnedStreamsAndPeWithoutARequiredGym() {
        ShapedCollege.Problem college = ShapedCollege.generate(ShapedCollege.Shape.smallStream());

        List<ProblemSnapshot.Unit> pinned = college.snapshot().units().stream()
                .filter(unit -> unit.pinned() != null).toList();

        assertThat(pinned).hasSize(4);
        assertThat(pinned).filteredOn(unit -> unit.teacherId() == null)
                .hasSize(2).allSatisfy(follower -> assertThat(follower.pinned().roomId()).isNull());
        assertThat(pinned.stream().map(unit -> unit.pinned().weekday() + "/" + unit.pinned().pair())
                .collect(Collectors.toSet())).hasSize(2);
        assertThat(college.snapshot().units()).filteredOn(unit -> unit.teacherRoomId() != null
                        && college.snapshot().rooms().stream().anyMatch(room -> room.id().equals(unit.teacherRoomId())
                        && room.type() == RoomType.GYM))
                .isNotEmpty().allSatisfy(unit -> assertThat(unit.requiredRoomType()).isNull());
    }

    /** Pairs a week of every group: occupied slots, a slot with two halves or two subgroups counts once. */
    private static Map<UUID, Integer> pairsPerWeek(ShapedCollege.Problem college) {
        Map<String, Assignment> placed = college.planted().stream()
                .collect(Collectors.toMap(Assignment::unitId, assignment -> assignment));
        Map<UUID, Set<String>> slots = new HashMap<>();
        for (ProblemSnapshot.Unit unit : college.snapshot().units()) {
            Assignment assignment = placed.get(unit.unitId());
            slots.computeIfAbsent(unit.groupId(), id -> new HashSet<>())
                    .add(assignment.weekday() + "/" + assignment.pair());
        }
        Map<UUID, Integer> pairs = new HashMap<>();
        slots.forEach((group, set) -> pairs.put(group, set.size()));
        assertThat(college.snapshot().units()).filteredOn(unit -> unit.weight() == UnitWeight.HALF).isNotEmpty();
        return pairs;
    }

    private static String slot(Map<String, Assignment> placed, ProblemSnapshot.Unit unit) {
        Assignment assignment = placed.get(unit.unitId());
        return assignment.weekday() + "/" + assignment.pair();
    }

    private static Set<Integer> weekdays(List<ProblemSnapshot.Timeslot> slots) {
        return slots.stream().map(ProblemSnapshot.Timeslot::weekday).collect(Collectors.toSet());
    }
}
