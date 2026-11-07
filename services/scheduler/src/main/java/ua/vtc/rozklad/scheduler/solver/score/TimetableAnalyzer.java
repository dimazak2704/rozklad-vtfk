package ua.vtc.rozklad.scheduler.solver.score;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.HardSoftScore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Room;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;

/**
 * Score analysis of a placed timetable: matches and score per constraint, computed with the same
 * domain rules as {@link TimetableConstraintProvider} and the same match granularity (a pair of
 * clashing lessons, a room overflow in one slot, a group-day with windows…). Timefold Community
 * has no score analysis API (it is a commercial feature), so this is done here; tests check that
 * the per-constraint scores add up to the solver's score.
 */
public final class TimetableAnalyzer {

    /** One row of the analysis; {@code impact} is the sum of match weights before the constraint weight. */
    public record ConstraintResult(TimetableConstraint constraint, int matchCount, long impact, HardSoftScore score) {
    }

    private TimetableAnalyzer() {
    }

    /** All constraints in declaration order (hard first), including those switched off by a zero weight. */
    public static List<ConstraintResult> analyze(Timetable timetable) {
        if (!timetable.isFullyPlaced()) {
            throw new IllegalArgumentException("only a fully placed timetable can be analysed");
        }
        List<LessonUnit> units = timetable.getUnits();
        Map<TimetableConstraint, Tally> tallies = new EnumMap<>(TimetableConstraint.class);
        for (TimetableConstraint constraint : TimetableConstraint.values()) {
            tallies.put(constraint, new Tally());
        }
        clashingPairs(tallies.get(TimetableConstraint.TEACHER_CONFLICT), units, LessonUnit::hasTeacher,
                unit -> new SlotKey(unit.getTeacherId(), unit.getTimeslot()), (a, b) -> true);
        clashingPairs(tallies.get(TimetableConstraint.COHORT_CONFLICT), units, unit -> true,
                unit -> new SlotKey(unit.getGroupId(), unit.getTimeslot()), LessonUnit::cohortsOverlap);
        roomOverflows(tallies.get(TimetableConstraint.ROOM_CONFLICT), units);
        perUnit(tallies.get(TimetableConstraint.ROOM_TYPE), units, LessonUnit::violatesRoomType,
                LessonUnit::weeklyOccurrences);
        perUnit(tallies.get(TimetableConstraint.ROOM_CAPACITY), units, LessonUnit::exceedsRoomCapacity,
                LessonUnit::weeklyOccurrences);
        perUnit(tallies.get(TimetableConstraint.OUTSIDE_SHIFT), units, LessonUnit::isOutsideShift,
                LessonUnit::weeklyOccurrences);
        perUnit(tallies.get(TimetableConstraint.TEACHER_UNAVAILABLE), units, LessonUnit::isTeacherUnavailable,
                LessonUnit::weeklyOccurrences);
        windows(tallies.get(TimetableConstraint.GROUP_WINDOWS), units, unit -> true,
                unit -> new DayKey(unit.getGroupId(), unit.getWeekday(), null),
                LaneCounts.COHORT_LANES, LessonUnit::cohortLanes);
        windows(tallies.get(TimetableConstraint.TEACHER_WINDOWS), units, LessonUnit::hasTeacher,
                unit -> new DayKey(unit.getTeacherId(), unit.getWeekday(), null),
                LessonUnit.WEEK_TYPES, LessonUnit::weekLanes);
        dayBalance(tallies.get(TimetableConstraint.BROKEN_DAY), units,
                unit -> new DayKey(unit.getGroupId(), unit.getWeekday(), null),
                balance -> balance.isBroken() ? balance.brokenness() : 0);
        perUnit(tallies.get(TimetableConstraint.LATE_PAIR), units,
                unit -> unit.positionInShift() >= TimetableConstraintProvider.FIRST_LATE_POSITION,
                unit -> (unit.positionInShift() - TimetableConstraintProvider.FIRST_LATE_POSITION + 1)
                        * unit.weeklyOccurrences());
        dayBalance(tallies.get(TimetableConstraint.DISCIPLINE_REPEAT), units,
                unit -> new DayKey(unit.getGroupId(), unit.getWeekday(), unit.getDisciplineId()),
                balance -> balance.excessOver(TimetableConstraintProvider.DISCIPLINE_PAIRS_PER_DAY));
        perUnit(tallies.get(TimetableConstraint.UNWANTED_SLOT), units, LessonUnit::isTeacherUnwanted,
                LessonUnit::weeklyOccurrences);
        perUnit(tallies.get(TimetableConstraint.ROOM_PREFERENCE), units, LessonUnit::isOutsideDesiredRoom,
                LessonUnit::weeklyOccurrences);
        pairedHalves(tallies.get(TimetableConstraint.HALF_PAIRING), units);

        ConstraintWeightOverrides<HardSoftScore> overrides = timetable.getConstraintWeightOverrides();
        List<ConstraintResult> results = new ArrayList<>();
        tallies.forEach((constraint, tally) -> results.add(new ConstraintResult(constraint, tally.matches,
                tally.impact, score(constraint, tally.impact, overrides))));
        return results;
    }

    /** Sum of all constraint scores — equals the solver's score of the same timetable. */
    public static HardSoftScore totalScore(List<ConstraintResult> results) {
        return results.stream().map(ConstraintResult::score).reduce(HardSoftScore.ZERO, HardSoftScore::add);
    }

    private static HardSoftScore score(TimetableConstraint constraint, long impact,
                                       ConstraintWeightOverrides<HardSoftScore> overrides) {
        HardSoftScore weight = overrides == null ? null : overrides.getConstraintWeight(constraint.id());
        if (weight == null) {
            weight = constraint.isHard() ? HardSoftScore.ONE_HARD : HardSoftScore.ONE_SOFT;
        }
        long signed = constraint == TimetableConstraint.HALF_PAIRING ? impact : -impact;
        return HardSoftScore.of(weight.hardScore() * signed, weight.softScore() * signed);
    }

    private static void perUnit(Tally tally, List<LessonUnit> units, Predicate<LessonUnit> violates,
                                ToIntFunction<LessonUnit> weight) {
        for (LessonUnit unit : units) {
            if (violates.test(unit)) {
                tally.add(weight.applyAsInt(unit));
            }
        }
    }

    private static void clashingPairs(Tally tally, List<LessonUnit> units, Predicate<LessonUnit> relevant,
                                      Function<LessonUnit, SlotKey> key, PairFilter overlap) {
        for (List<LessonUnit> sameSlot : group(units, relevant, key).values()) {
            for (int i = 0; i < sameSlot.size(); i++) {
                for (int j = i + 1; j < sameSlot.size(); j++) {
                    LessonUnit first = sameSlot.get(i);
                    LessonUnit second = sameSlot.get(j);
                    int weeks = first.weeksSharedWith(second);
                    if (weeks > 0 && overlap.test(first, second)) {
                        tally.add(weeks);
                    }
                }
            }
        }
    }

    private static void roomOverflows(Tally tally, List<LessonUnit> units) {
        Map<SlotKey, List<LessonUnit>> byRoom = group(units, unit -> !unit.getRoom().isNone(),
                unit -> new SlotKey(unit.getRoom().getId(), unit.getTimeslot()));
        for (List<LessonUnit> sameRoom : byRoom.values()) {
            Room room = sameRoom.getFirst().getRoom();
            long numerator = sameRoom.stream().mapToLong(LessonUnit::numeratorOccupancy).sum();
            long denominator = sameRoom.stream().mapToLong(LessonUnit::denominatorOccupancy).sum();
            long overflow = Math.max(0, numerator - room.getMaxParallel())
                    + Math.max(0, denominator - room.getMaxParallel());
            if (overflow > 0) {
                tally.add(overflow);
            }
        }
    }

    private static void windows(Tally tally, List<LessonUnit> units, Predicate<LessonUnit> relevant,
                                Function<LessonUnit, DayKey> key, int lanes, Function<LessonUnit, int[]> lanesOf) {
        for (LaneCounts counts : laneCounts(units, relevant, key, lanes, lanesOf).values()) {
            int gaps = counts.gaps();
            if (gaps > 0) {
                tally.add(gaps);
            }
        }
    }

    private static void dayBalance(Tally tally, List<LessonUnit> units, Function<LessonUnit, DayKey> key,
                                   ToIntFunction<WeekBalance> penalty) {
        for (LaneCounts counts : laneCounts(units, unit -> true, key, LessonUnit.WEEK_TYPES,
                LessonUnit::weekLanes).values()) {
            int value = penalty.applyAsInt(new WeekBalance(
                    counts.distinctPairs(LessonUnit.NUMERATOR_WEEK), counts.firstPair(LessonUnit.NUMERATOR_WEEK),
                    counts.distinctPairs(LessonUnit.DENOMINATOR_WEEK), counts.firstPair(LessonUnit.DENOMINATOR_WEEK)));
            if (value > 0) {
                tally.add(value);
            }
        }
    }

    private static void pairedHalves(Tally tally, List<LessonUnit> units) {
        Map<CohortSlotKey, List<LessonUnit>> byCohortSlot = new HashMap<>();
        for (LessonUnit unit : units) {
            if (unit.isHalf()) {
                byCohortSlot.computeIfAbsent(new CohortSlotKey(unit.getGroupId(), unit.getSubgroup(),
                        unit.getTimeslot()), slot -> new ArrayList<>()).add(unit);
            }
        }
        for (List<LessonUnit> sameSlot : byCohortSlot.values()) {
            for (int i = 0; i < sameSlot.size(); i++) {
                for (int j = i + 1; j < sameSlot.size(); j++) {
                    if (sameSlot.get(i).getParity() != sameSlot.get(j).getParity()) {
                        tally.add(1);
                    }
                }
            }
        }
    }

    private static Map<DayKey, LaneCounts> laneCounts(Collection<LessonUnit> units, Predicate<LessonUnit> relevant,
                                                      Function<LessonUnit, DayKey> key, int lanes,
                                                      Function<LessonUnit, int[]> lanesOf) {
        Map<DayKey, LaneCounts> result = new HashMap<>();
        for (LessonUnit unit : units) {
            if (relevant.test(unit)) {
                result.computeIfAbsent(key.apply(unit), day -> new LaneCounts(lanes))
                        .add(lanesOf.apply(unit), unit.getPair());
            }
        }
        return result;
    }

    private static Map<SlotKey, List<LessonUnit>> group(Collection<LessonUnit> units, Predicate<LessonUnit> relevant,
                                                        Function<LessonUnit, SlotKey> key) {
        Map<SlotKey, List<LessonUnit>> result = new HashMap<>();
        for (LessonUnit unit : units) {
            if (relevant.test(unit)) {
                result.computeIfAbsent(key.apply(unit), slot -> new ArrayList<>()).add(unit);
            }
        }
        return result;
    }

    @FunctionalInterface
    private interface PairFilter {
        boolean test(LessonUnit first, LessonUnit second);
    }

    private record SlotKey(UUID owner, Timeslot timeslot) {
    }

    private record CohortSlotKey(UUID groupId, int subgroup, Timeslot timeslot) {
    }

    private record DayKey(UUID owner, int weekday, UUID discipline) {
    }

    private static final class Tally {
        int matches;
        long impact;

        void add(long weight) {
            matches++;
            impact += weight;
        }
    }
}
