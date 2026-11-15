package ua.vtc.rozklad.scheduler.solver.score;

import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.BROKEN_DAY;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.COHORT_CONFLICT;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.DISCIPLINE_REPEAT;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.DISCIPLINE_TWICE;
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
 * the unit weights. The soft rules added after the generator review (waits between shifts,
 * stability, subgroups, daily balance, a discipline twice a day) are preferences with a weight the
 * dispatcher can set to 0, not college rules.
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
                groupWindows(factory),
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

    // ---- soft: group-day rules (one shared grouping)

    /** Free pairs between lessons: per kind of week the worst cohort of the group (B10). */
    Constraint groupWindows(ConstraintFactory factory) {
        return groupDays(factory)
                .filter((group, weekday, day) -> day.windows() > 0)
                .penalize(HardSoftScore.ONE_SOFT, (group, weekday, day) -> day.windows())
                .asConstraint(GROUP_WINDOWS.id());
    }

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

    /** A subgroup studies while another cohort of the group has no lesson: per pair and kind of week. */
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

    /** The 4th pair of the shift and later ({@code LatePairs}), the later the worse. */
    Constraint latePairs(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .filter(LessonUnit::isLate)
                .penalize(HardSoftScore.ONE_SOFT, LessonUnit::latePenalty)
                .asConstraint(LATE_PAIR.id());
    }

    /**
     * Subgroup copies of one occurrence taught by the same teacher cannot be parallel, so they should
     * follow each other on one day. Penalized per kind of week both meet in.
     */
    Constraint siblingsApart(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .filter(LessonUnit::hasSiblingWithTeacher)
                .join(factory.forEach(LessonUnit.class).filter(LessonUnit::hasSiblingWithTeacher),
                        Joiners.equal(LessonUnit::getSiblingKey),
                        Joiners.equal(LessonUnit::getTeacherId),
                        Joiners.lessThan(LessonUnit::getId))
                .filter((first, second) -> !first.isAdjacentTo(second) && first.weeksSharedWith(second) > 0)
                .penalize(HardSoftScore.ONE_SOFT, LessonUnit::weeksSharedWith)
                .asConstraint(SIBLINGS_APART.id());
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
     * sits in another kind of room, per week occurrence. Soft on purpose (the college has not confirmed the rule,
     * DOMAIN §13 Q6): when the hall is full or closed, PE may go to a classroom at this cost instead of leaving
     * the run infeasible; weight 0 lets it go there freely.
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
