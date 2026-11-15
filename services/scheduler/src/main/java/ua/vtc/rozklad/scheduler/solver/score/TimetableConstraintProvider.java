package ua.vtc.rozklad.scheduler.solver.score;

import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.BROKEN_DAY;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.COHORT_CONFLICT;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.DISCIPLINE_REPEAT;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.DISCIPLINE_TWICE;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.FRIDAY_OVERLOAD;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.GROUP_ONE_PAIR_DAY;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.GROUP_WINDOWS;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.HALF_PAIRING;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.LATE_PAIR;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.OUTSIDE_SHIFT;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.ROOM_CAPACITY;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.ROOM_CHANGED;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.ROOM_CONFLICT;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.ROOM_PREFERENCE;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.ROOM_TYPE;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.SIBLINGS_APART;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.SPECIAL_ROOM_LOST;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.SUBGROUPS_APART;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.SUBGROUP_ALONE;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.TEACHER_CONFLICT;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.TEACHER_EXTRA_DAY;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.TEACHER_ONE_PAIR_DAY;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.TEACHER_OVERLOAD;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.TEACHER_SHIFT_WAIT;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.TEACHER_UNAVAILABLE;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.TEACHER_WINDOWS;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.UNIT_MOVED;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.UNWANTED_SLOT;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;
import ai.timefold.solver.core.api.score.stream.bi.BiConstraintStream;
import ai.timefold.solver.core.api.score.stream.quad.QuadConstraintStream;
import ai.timefold.solver.core.api.score.stream.tri.TriConstraintStream;
import java.util.UUID;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Room;
import ua.vtc.rozklad.scheduler.solver.domain.Teacher;

/**
 * Hard and soft rules of the college timetable (DOMAIN §5). Penalties count week occurrences: a
 * clash of two BOTH lessons costs 2 (numerator and denominator week), a clash involving a half
 * costs 1. Soft weights come from the request ({@link ConstraintWeights}); the weights here are
 * the unit weights. The owner's answers of 15.11.2026 (DOMAIN §13) made subgroups of one discipline
 * together, no group windows and at most the Friday limit of pairs hard rules; one-pair days and PE in the
 * hall are confirmed rules kept soft with a strong default weight, and the 4th pair of the shift is normal (its
 * preference is off by default). The other soft rules are trade-offs the dispatcher can weigh or switch off.
 *
 * <p>Rules over the same day share one grouping node: the group-day rules read {@link GroupDay}, the
 * teacher-day rules {@link TeacherDay}, the discipline rules {@link DisciplineDay}.
 */
public class TimetableConstraintProvider implements ConstraintProvider {

    /** A discipline may appear at most twice a day before «three and more» is penalized. */
    static final int DISCIPLINE_PAIRS_PER_DAY = 2;
    /** «Дисципліна двічі на день»: every pair of a discipline above one for a student. */
    static final int DISCIPLINE_TWICE_LIMIT = 1;

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
                teacherConflict(factory),
                cohortConflict(factory),
                roomConflict(factory),
                roomType(factory),
                roomCapacity(factory),
                outsideShift(factory),
                teacherUnavailable(factory),
                subgroupsApart(factory),
                groupWindows(factory),
                fridayOverload(factory),
                teacherWindows(factory),
                teacherShiftWait(factory),
                brokenDays(factory),
                latePairs(factory),
                groupOnePairDays(factory),
                subgroupAlone(factory),
                siblingsApart(factory),
                teacherOnePairDays(factory),
                teacherExtraDays(factory),
                teacherOverload(factory),
                disciplineTwice(factory),
                disciplineRepeat(factory),
                unwantedSlots(factory),
                roomPreference(factory),
                unitMoved(factory),
                roomChanged(factory),
                specialRoomLost(factory),
                halfPairing(factory)
        };
    }

    // ---- hard

    Constraint teacherConflict(ConstraintFactory factory) {
        return factory.forEachUniquePair(LessonUnit.class,
                        Joiners.equal(LessonUnit::getTimeslot),
                        Joiners.equal(LessonUnit::getTeacherId))
                .filter((first, second) -> first.hasTeacher() && first.weeksSharedWith(second) > 0)
                .penalize(HardSoftScore.ONE_HARD, LessonUnit::weeksSharedWith)
                .asConstraint(TEACHER_CONFLICT.id());
    }

    /** Whole group vs any subgroup, or the same subgroup twice, in overlapping weeks. */
    Constraint cohortConflict(ConstraintFactory factory) {
        return factory.forEachUniquePair(LessonUnit.class,
                        Joiners.equal(LessonUnit::getTimeslot),
                        Joiners.equal(LessonUnit::getGroupId))
                .filter((first, second) -> first.cohortsOverlap(second) && first.weeksSharedWith(second) > 0)
                .penalize(HardSoftScore.ONE_HARD, LessonUnit::weeksSharedWith)
                .asConstraint(COHORT_CONFLICT.id());
    }

    /** More lessons in a room than its {@code maxParallel} in the numerator or denominator week. */
    Constraint roomConflict(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .filter(unit -> !unit.getRoom().isNone())
                .groupBy(LessonUnit::getRoom, LessonUnit::getTimeslot,
                        ConstraintCollectors.sum(LessonUnit::numeratorOccupancy),
                        ConstraintCollectors.sum(LessonUnit::denominatorOccupancy))
                .filter((room, timeslot, numerator, denominator) ->
                        overflow(room, numerator) + overflow(room, denominator) > 0)
                .penalize(HardSoftScore.ONE_HARD, (room, timeslot, numerator, denominator) ->
                        overflow(room, numerator) + overflow(room, denominator))
                .asConstraint(ROOM_CONFLICT.id());
    }

    Constraint roomType(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .filter(LessonUnit::violatesRoomType)
                .penalize(HardSoftScore.ONE_HARD, LessonUnit::weeklyOccurrences)
                .asConstraint(ROOM_TYPE.id());
    }

    Constraint roomCapacity(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .filter(LessonUnit::exceedsRoomCapacity)
                .penalize(HardSoftScore.ONE_HARD, LessonUnit::weeklyOccurrences)
                .asConstraint(ROOM_CAPACITY.id());
    }

    Constraint outsideShift(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .filter(LessonUnit::isOutsideShift)
                .penalize(HardSoftScore.ONE_HARD, LessonUnit::weeklyOccurrences)
                .asConstraint(OUTSIDE_SHIFT.id());
    }

    Constraint teacherUnavailable(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .filter(LessonUnit::isTeacherUnavailable)
                .penalize(HardSoftScore.ONE_HARD, LessonUnit::weeklyOccurrences)
                .asConstraint(TEACHER_UNAVAILABLE.id());
    }

    /**
     * The subgroup copies of one weekly occurrence of a split discipline (equal sibling key) with different
     * teachers are not in the same slot and weeks (owner's rule, 15.11.2026: both subgroups at the same time).
     * Only free units carry a sibling key (core sends none on pinned lessons), and a pair of two pinned copies
     * is skipped anyway, so a pinned pair that already breaks the rule never makes a run infeasible. A key whose
     * copies cannot all be parallel by the data (one teacher for two of three copies, no common free slot) is
     * left to the soft rule below ({@link LessonUnit#mustBeParallelTo}), so the data alone cannot make a run
     * infeasible either.
     */
    Constraint subgroupsApart(ConstraintFactory factory) {
        return siblingPairs(factory)
                .filter((first, second) -> first.mustBeParallelTo(second) && !first.isTogetherWith(second))
                .penalize(HardSoftScore.ONE_HARD, LessonUnit::apartPenalty)
                .asConstraint(SUBGROUPS_APART.id());
    }

    /**
     * Free pairs between lessons, per kind of week the worst cohort of the group (B10), on a day the run may change
     * (owner's rule of 15.11.2026: «вікна групам не варто лишати, краще викладачам вікна»). A lone subgroup pair at the
     * edge of the day is not a window ({@link GroupDay#windows()}), so one teacher's two subgroups one after the other
     * (the soft rule below) may stand first or last. A day with only pinned lessons is exempt: a window between lessons
     * pinned outside the scope never makes a run infeasible. Teacher windows stay a soft trade-off.
     */
    Constraint groupWindows(ConstraintFactory factory) {
        return groupDays(factory)
                .filter((group, weekday, day) -> day.hardWindows() > 0)
                .penalize(HardSoftScore.ONE_HARD, (group, weekday, day) -> day.hardWindows())
                .asConstraint(GROUP_WINDOWS.id());
    }

    /**
     * More occupied pairs on Friday than the group's limit (owner's rule of 15.11.2026: Friday is an unloading day; the
     * college setting «Максимум пар у пʼятницю», default 3), per kind of week, on a day the run may change.
     */
    Constraint fridayOverload(ConstraintFactory factory) {
        return groupDays(factory)
                .filter((group, weekday, day) -> day.hardPairsAboveDayLimit() > 0)
                .penalize(HardSoftScore.ONE_HARD, (group, weekday, day) -> day.hardPairsAboveDayLimit())
                .asConstraint(FRIDAY_OVERLOAD.id());
    }

    // ---- soft: group-day rules (one shared grouping)

    /** «Рваний день»: the numerator and denominator weeks of a group-day differ in pairs or start. */
    Constraint brokenDays(ConstraintFactory factory) {
        return groupDays(factory)
                .filter((group, weekday, day) -> day.brokenness() > 0)
                .penalize(HardSoftScore.ONE_SOFT, (group, weekday, day) -> day.brokenness())
                .asConstraint(BROKEN_DAY.id());
    }

    /** A kind of week in which the group comes for a single pair. */
    Constraint groupOnePairDays(ConstraintFactory factory) {
        return groupDays(factory)
                .filter((group, weekday, day) -> day.onePairWeeks() > 0)
                .penalize(HardSoftScore.ONE_SOFT, (group, weekday, day) -> day.onePairWeeks())
                .asConstraint(GROUP_ONE_PAIR_DAY.id());
    }

    /**
     * A subgroup studies while another cohort of the group has no lesson: per pair and kind of week. Next to the
     * hard rule above it catches what a sibling key does not cover: a subgroup lesson without a counterpart in
     * the load, or two split disciplines crossed.
     */
    Constraint subgroupAlone(ConstraintFactory factory) {
        return groupDays(factory)
                .filter((group, weekday, day) -> day.loneSubgroupSlots() > 0)
                .penalize(HardSoftScore.ONE_SOFT, (group, weekday, day) -> day.loneSubgroupSlots())
                .asConstraint(SUBGROUP_ALONE.id());
    }

    // ---- soft: teacher-day and teacher-week rules

    /** Free pairs inside a teacher's day, except a wait between a lesson of one shift and of another. */
    Constraint teacherWindows(ConstraintFactory factory) {
        return teacherDays(factory)
                .filter((teacher, weekday, day) -> day.windows() > 0)
                .penalize(HardSoftScore.ONE_SOFT, (teacher, weekday, day) -> day.windows())
                .asConstraint(TEACHER_WINDOWS.id());
    }

    /** Free pairs between the last lesson with a group of one shift and the next one with another shift. */
    Constraint teacherShiftWait(ConstraintFactory factory) {
        return teacherDays(factory)
                .filter((teacher, weekday, day) -> day.shiftWaits() > 0)
                .penalize(HardSoftScore.ONE_SOFT, (teacher, weekday, day) -> day.shiftWaits())
                .asConstraint(TEACHER_SHIFT_WAIT.id());
    }

    /** A kind of week in which the teacher comes for a single pair. */
    Constraint teacherOnePairDays(ConstraintFactory factory) {
        return teacherDays(factory)
                .filter((teacher, weekday, day) -> day.onePairWeeks() > 0)
                .penalize(HardSoftScore.ONE_SOFT, (teacher, weekday, day) -> day.onePairWeeks())
                .asConstraint(TEACHER_ONE_PAIR_DAY.id());
    }

    /** Pairs above the teacher's daily limit, per kind of week (a teacher without a limit never matches). */
    Constraint teacherOverload(ConstraintFactory factory) {
        return teacherDays(factory)
                .filter((teacher, weekday, day) -> overload(teacher, day) > 0)
                .penalize(HardSoftScore.ONE_SOFT, (teacher, weekday, day) -> overload(teacher, day))
                .asConstraint(TEACHER_OVERLOAD.id());
    }

    /** Teaching days above the teacher's baseline (see {@code TimetableProblemFactory}), per kind of week. */
    Constraint teacherExtraDays(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .filter(LessonUnit::hasTeacher)
                .groupBy(LessonUnit::getTeacher, DayCollectors.teacherWeek())
                .filter((teacher, week) -> week.extraDays(teacher) > 0)
                .penalize(HardSoftScore.ONE_SOFT, (teacher, week) -> week.extraDays(teacher))
                .asConstraint(TEACHER_EXTRA_DAY.id());
    }

    // ---- soft: lesson rules

    /**
     * Subgroup copies that cannot be parallel are not on neighbouring pairs of one day in the same weeks: one
     * teacher teaches both, or the key's copies cannot all be parallel by the data (then two teachers' copies may
     * also be parallel). Soft with the strongest default ({@code subgroupsTogether}): a hard version for one teacher
     * left full second shifts with interlocked labs infeasible (scheduler.md §11 «Owner's rules»).
     */
    Constraint siblingsApart(ConstraintFactory factory) {
        return siblingPairs(factory)
                .filter((first, second) -> !first.mustBeParallelTo(second) && !first.isTogetherWith(second))
                .penalize(HardSoftScore.ONE_SOFT, LessonUnit::apartPenalty)
                .asConstraint(SIBLINGS_APART.id());
    }

    /** The 4th pair of the shift and later ({@code LatePairs}), the later the worse. */
    Constraint latePairs(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .filter(LessonUnit::isLate)
                .penalize(HardSoftScore.ONE_SOFT, LessonUnit::latePenalty)
                .asConstraint(LATE_PAIR.id());
    }

    /** A discipline more than once a day for a student (a subgroup counts its own pairs). */
    Constraint disciplineTwice(ConstraintFactory factory) {
        return disciplineDays(factory)
                .filter((group, weekday, discipline, day) -> day.cohortExcessOver(DISCIPLINE_TWICE_LIMIT) > 0)
                .penalize(HardSoftScore.ONE_SOFT,
                        (group, weekday, discipline, day) -> day.cohortExcessOver(DISCIPLINE_TWICE_LIMIT))
                .asConstraint(DISCIPLINE_TWICE.id());
    }

    /** The same discipline three or more times a day for a group (subgroups in one pair count once). */
    Constraint disciplineRepeat(ConstraintFactory factory) {
        return disciplineDays(factory)
                .filter((group, weekday, discipline, day) -> day.excessOver(DISCIPLINE_PAIRS_PER_DAY) > 0)
                .penalize(HardSoftScore.ONE_SOFT,
                        (group, weekday, discipline, day) -> day.excessOver(DISCIPLINE_PAIRS_PER_DAY))
                .asConstraint(DISCIPLINE_REPEAT.id());
    }

    Constraint unwantedSlots(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .filter(LessonUnit::isTeacherUnwanted)
                .penalize(HardSoftScore.ONE_SOFT, LessonUnit::weeklyOccurrences)
                .asConstraint(UNWANTED_SLOT.id());
    }

    /**
     * The lesson is not in its desired room: the load item's preferred room, else the teacher's usual
     * room (round 7: the room belongs to the teacher), else the home room of an older snapshot.
     */
    Constraint roomPreference(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .filter(LessonUnit::isOutsideDesiredRoom)
                .penalize(HardSoftScore.ONE_SOFT, LessonUnit::weeklyOccurrences)
                .asConstraint(ROOM_PREFERENCE.id());
    }

    /** Stability: a unit in another slot or week than in the base version, per week occurrence. */
    Constraint unitMoved(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .filter(LessonUnit::isMovedFromBase)
                .penalize(HardSoftScore.ONE_SOFT, LessonUnit::weeklyOccurrences)
                .asConstraint(UNIT_MOVED.id());
    }

    /** Stability: a unit kept its base slot but not its base room, per week occurrence. */
    Constraint roomChanged(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .filter(LessonUnit::isRoomChangedFromBase)
                .penalize(HardSoftScore.ONE_SOFT, LessonUnit::weeklyOccurrences)
                .asConstraint(ROOM_CHANGED.id());
    }

    /**
     * A lesson of a load item whose base lessons all stood in special-purpose rooms (sports hall, sports ground)
     * sits in another kind of room, per week occurrence. The college confirmed the rule (DOMAIN §13 Q6), but it
     * stays soft with a strong default: when the hall is full or closed, PE goes to a classroom at this cost
     * instead of leaving the run infeasible (the hall's places are the only reason).
     */
    Constraint specialRoomLost(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .filter(LessonUnit::isOutsideBaseRoomTypes)
                .penalize(HardSoftScore.ONE_SOFT, LessonUnit::weeklyOccurrences)
                .asConstraint(SPECIAL_ROOM_LOST.id());
    }

    /** Two halves of one cohort share a slot as numerator and denominator: the day stays even. */
    Constraint halfPairing(ConstraintFactory factory) {
        return factory.forEachUniquePair(LessonUnit.class,
                        Joiners.equal(LessonUnit::getTimeslot),
                        Joiners.equal(LessonUnit::getGroupId),
                        Joiners.equal(LessonUnit::getSubgroup))
                .filter((first, second) -> first.isHalf() && second.isHalf()
                        && first.getParity() != second.getParity())
                .reward(HardSoftScore.ONE_SOFT)
                .asConstraint(HALF_PAIRING.id());
    }

    // ---- shared groupings (equal streams share one node)

    /** Pairs of subgroup copies with one sibling key, at least one of them free. */
    private static BiConstraintStream<LessonUnit, LessonUnit> siblingPairs(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .filter(LessonUnit::hasSiblingKey)
                .join(factory.forEach(LessonUnit.class).filter(LessonUnit::hasSiblingKey),
                        Joiners.equal(LessonUnit::getSiblingKey),
                        Joiners.lessThan(LessonUnit::getId))
                .filter((first, second) -> !first.isPinned() || !second.isPinned());
    }

    private static TriConstraintStream<UUID, Integer, GroupDay> groupDays(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .groupBy(LessonUnit::getGroupId, LessonUnit::getWeekday, DayCollectors.groupDay());
    }

    private static TriConstraintStream<Teacher, Integer, TeacherDay> teacherDays(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .filter(LessonUnit::hasTeacher)
                .groupBy(LessonUnit::getTeacher, LessonUnit::getWeekday, DayCollectors.teacherDay());
    }

    private static QuadConstraintStream<UUID, Integer, UUID, DisciplineDay> disciplineDays(
            ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .groupBy(LessonUnit::getGroupId, LessonUnit::getWeekday, LessonUnit::getDisciplineId,
                        DayCollectors.disciplineDay());
    }

    /** Pairs above the teacher's daily limit in both kinds of week. */
    static int overload(Teacher teacher, TeacherDay day) {
        return teacher.pairsAboveLimit(day.numeratorPairs()) + teacher.pairsAboveLimit(day.denominatorPairs());
    }

    private static long overflow(Room room, long lessons) {
        return Math.max(0, lessons - room.getMaxParallel());
    }
}
