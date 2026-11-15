package ua.vtc.rozklad.scheduler.solver.score;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.HardSoftScore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Room;
import ua.vtc.rozklad.scheduler.solver.domain.Teacher;
import ua.vtc.rozklad.scheduler.solver.domain.Timeslot;
import ua.vtc.rozklad.scheduler.solver.domain.Timetable;

/**
 * Score analysis of a placed timetable: matches and score per constraint, computed with the same
 * domain rules as {@link TimetableConstraintProvider} and the same match granularity (a pair of
 * clashing lessons, a room overflow in one slot, a group-day with windows…). Timefold Community
 * has no score analysis API (it is a commercial feature), so this is done here; tests check that
 * the per-constraint scores add up to the solver's score.
 *
 * <p>Besides all matches, each row counts the matches <em>in scope</em>: those that involve at least
 * one free (non-pinned) unit. A group-day, teacher-day or group-day-discipline match is in scope when
 * that group or teacher has a free unit that day, a teacher-week match when the teacher has a free unit.
 */
public final class TimetableAnalyzer {

    /**
     * One row of the analysis; {@code impact} is the sum of match weights before the constraint weight,
     * {@code scopeMatchCount} the matches that involve a free unit.
     */
    public record ConstraintResult(TimetableConstraint constraint, int matchCount, long impact, HardSoftScore score,
                                   int scopeMatchCount) {
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
        Scope scope = new Scope(units);

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

        groupDays(tallies, units, scope);
        teacherDays(tallies, units, scope);
        teacherWeeks(tallies.get(TimetableConstraint.TEACHER_EXTRA_DAY), units, scope);
        disciplineDays(tallies, units, scope);
        siblingsApart(tallies.get(TimetableConstraint.SIBLINGS_APART), units);

        perUnit(tallies.get(TimetableConstraint.LATE_PAIR), units, LessonUnit::isLate, LessonUnit::latePenalty);
        perUnit(tallies.get(TimetableConstraint.UNWANTED_SLOT), units, LessonUnit::isTeacherUnwanted,
                LessonUnit::weeklyOccurrences);
        perUnit(tallies.get(TimetableConstraint.ROOM_PREFERENCE), units, LessonUnit::isOutsideDesiredRoom,
                LessonUnit::weeklyOccurrences);
        perUnit(tallies.get(TimetableConstraint.UNIT_MOVED), units, LessonUnit::isMovedFromBase,
                LessonUnit::weeklyOccurrences);
        perUnit(tallies.get(TimetableConstraint.ROOM_CHANGED), units, LessonUnit::isRoomChangedFromBase,
                LessonUnit::weeklyOccurrences);
        perUnit(tallies.get(TimetableConstraint.SPECIAL_ROOM_LOST), units, LessonUnit::isOutsideBaseRoomTypes,
                LessonUnit::weeklyOccurrences);
        pairedHalves(tallies.get(TimetableConstraint.HALF_PAIRING), units);

        ConstraintWeightOverrides<HardSoftScore> overrides = timetable.getConstraintWeightOverrides();
        List<ConstraintResult> results = new ArrayList<>();
        tallies.forEach((constraint, tally) -> results.add(new ConstraintResult(constraint, tally.matches,
                tally.impact, score(constraint, tally.impact, overrides), tally.scopeMatches)));
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
        long signed = constraint.isBonus() ? impact : -impact;
        return HardSoftScore.of(weight.hardScore() * signed, weight.softScore() * signed);
    }

    private static void perUnit(Tally tally, List<LessonUnit> units, Predicate<LessonUnit> violates,
                                ToIntFunction<LessonUnit> weight) {
        for (LessonUnit unit : units) {
            if (violates.test(unit)) {
                tally.add(weight.applyAsInt(unit), !unit.isPinned());
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
                        tally.add(weeks, !first.isPinned() || !second.isPinned());
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
                tally.add(overflow, sameRoom.stream().anyMatch(unit -> !unit.isPinned()));
            }
        }
    }

    private static void groupDays(Map<TimetableConstraint, Tally> tallies, List<LessonUnit> units, Scope scope) {
        Map<DayKey, GroupDay.Counts> days = states(units, unit -> true,
                unit -> new DayKey(unit.getGroupId(), unit.getWeekday(), null), GroupDay.Counts::new,
                GroupDay.Counts::add);
        days.forEach((key, counts) -> {
            GroupDay day = counts.result();
            boolean inScope = scope.groupDays.contains(key);
            tallies.get(TimetableConstraint.GROUP_WINDOWS).addIfPositive(day.windows(), inScope);
            tallies.get(TimetableConstraint.BROKEN_DAY).addIfPositive(day.brokenness(), inScope);
            tallies.get(TimetableConstraint.GROUP_ONE_PAIR_DAY).addIfPositive(day.onePairWeeks(), inScope);
            tallies.get(TimetableConstraint.SUBGROUP_ALONE).addIfPositive(day.loneSubgroupSlots(), inScope);
        });
    }

    private static void teacherDays(Map<TimetableConstraint, Tally> tallies, List<LessonUnit> units, Scope scope) {
        Map<TeacherDayKey, TeacherDay.Counts> days = states(units, LessonUnit::hasTeacher,
                unit -> new TeacherDayKey(unit.getTeacher(), unit.getWeekday()), TeacherDay.Counts::new,
                TeacherDay.Counts::add);
        days.forEach((key, counts) -> {
            TeacherDay day = counts.result();
            boolean inScope = scope.teacherDays.contains(new DayKey(key.teacher().getId(), key.weekday(), null));
            tallies.get(TimetableConstraint.TEACHER_WINDOWS).addIfPositive(day.windows(), inScope);
            tallies.get(TimetableConstraint.TEACHER_SHIFT_WAIT).addIfPositive(day.shiftWaits(), inScope);
            tallies.get(TimetableConstraint.TEACHER_ONE_PAIR_DAY).addIfPositive(day.onePairWeeks(), inScope);
            tallies.get(TimetableConstraint.TEACHER_OVERLOAD)
                    .addIfPositive(TimetableConstraintProvider.overload(key.teacher(), day), inScope);
        });
    }

    private static void teacherWeeks(Tally tally, List<LessonUnit> units, Scope scope) {
        Map<Teacher, TeacherWeek.Counts> weeks = states(units, LessonUnit::hasTeacher, LessonUnit::getTeacher,
                TeacherWeek.Counts::new, TeacherWeek.Counts::add);
        weeks.forEach((teacher, counts) -> tally.addIfPositive(counts.result().extraDays(teacher),
                scope.teachers.contains(teacher.getId())));
    }

    private static void disciplineDays(Map<TimetableConstraint, Tally> tallies, List<LessonUnit> units,
                                       Scope scope) {
        Map<DayKey, DisciplineDay.Counts> days = states(units, unit -> true,
                unit -> new DayKey(unit.getGroupId(), unit.getWeekday(), unit.getDisciplineId()),
                DisciplineDay.Counts::new, DisciplineDay.Counts::add);
        days.forEach((key, counts) -> {
            DisciplineDay day = counts.result();
            boolean inScope = scope.groupDays.contains(new DayKey(key.owner(), key.weekday(), null));
            tallies.get(TimetableConstraint.DISCIPLINE_TWICE).addIfPositive(
                    day.cohortExcessOver(TimetableConstraintProvider.DISCIPLINE_TWICE_LIMIT), inScope);
            tallies.get(TimetableConstraint.DISCIPLINE_REPEAT).addIfPositive(
                    day.excessOver(TimetableConstraintProvider.DISCIPLINE_PAIRS_PER_DAY), inScope);
        });
    }

    private static void siblingsApart(Tally tally, List<LessonUnit> units) {
        Map<SiblingKey, List<LessonUnit>> siblings = new HashMap<>();
        for (LessonUnit unit : units) {
            if (unit.hasSiblingWithTeacher()) {
                siblings.computeIfAbsent(new SiblingKey(unit.getSiblingKey(), unit.getTeacherId()),
                        key -> new ArrayList<>()).add(unit);
            }
        }
        for (List<LessonUnit> same : siblings.values()) {
            for (int i = 0; i < same.size(); i++) {
                for (int j = i + 1; j < same.size(); j++) {
                    LessonUnit first = same.get(i);
                    LessonUnit second = same.get(j);
                    int weeks = first.weeksSharedWith(second);
                    if (weeks > 0 && !first.isAdjacentTo(second)) {
                        tally.add(weeks, !first.isPinned() || !second.isPinned());
                    }
                }
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
                    LessonUnit first = sameSlot.get(i);
                    LessonUnit second = sameSlot.get(j);
                    if (first.getParity() != second.getParity()) {
                        tally.add(1, !first.isPinned() || !second.isPinned());
                    }
                }
            }
        }
    }

    private static <K, S> Map<K, S> states(Collection<LessonUnit> units, Predicate<LessonUnit> relevant,
                                           Function<LessonUnit, K> key, Supplier<S> supplier,
                                           BiFunction<S, LessonUnit, Runnable> add) {
        Map<K, S> result = new HashMap<>();
        for (LessonUnit unit : units) {
            if (relevant.test(unit)) {
                add.apply(result.computeIfAbsent(key.apply(unit), ignored -> supplier.get()), unit);
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

    private record TeacherDayKey(Teacher teacher, int weekday) {
    }

    private record SiblingKey(String siblingKey, UUID teacherId) {
    }

    /** Group-days, teacher-days and teachers that have at least one free unit. */
    private static final class Scope {
        final Set<DayKey> groupDays = new HashSet<>();
        final Set<DayKey> teacherDays = new HashSet<>();
        final Set<UUID> teachers = new HashSet<>();

        Scope(List<LessonUnit> units) {
            for (LessonUnit unit : units) {
                if (!unit.isPinned()) {
                    groupDays.add(new DayKey(unit.getGroupId(), unit.getWeekday(), null));
                    if (unit.hasTeacher()) {
                        teacherDays.add(new DayKey(unit.getTeacherId(), unit.getWeekday(), null));
                        teachers.add(unit.getTeacherId());
                    }
                }
            }
        }
    }

    private static final class Tally {
        int matches;
        int scopeMatches;
        long impact;

        void add(long weight, boolean inScope) {
            matches++;
            impact += weight;
            if (inScope) {
                scopeMatches++;
            }
        }

        void addIfPositive(long weight, boolean inScope) {
            if (weight > 0) {
                add(weight, inScope);
            }
        }
    }
}
