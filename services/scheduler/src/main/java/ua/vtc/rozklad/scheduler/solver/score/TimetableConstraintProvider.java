package ua.vtc.rozklad.scheduler.solver.score;

import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.BROKEN_DAY;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.COHORT_CONFLICT;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.DISCIPLINE_REPEAT;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.GROUP_WINDOWS;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.HALF_PAIRING;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.LATE_PAIR;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.OUTSIDE_SHIFT;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.ROOM_CAPACITY;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.ROOM_CONFLICT;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.ROOM_PREFERENCE;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.ROOM_TYPE;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.TEACHER_CONFLICT;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.TEACHER_UNAVAILABLE;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.TEACHER_WINDOWS;
import static ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint.UNWANTED_SLOT;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintCollectors;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;
import ua.vtc.rozklad.scheduler.solver.domain.LessonUnit;
import ua.vtc.rozklad.scheduler.solver.domain.Room;

/**
 * Hard and soft rules of the college timetable (DOMAIN §5). Penalties count week occurrences: a
 * clash of two BOTH lessons costs 2 (numerator and denominator week), a clash involving a half
 * costs 1. Soft weights come from the request ({@link ConstraintWeights}); the weights here are
 * the unit weights.
 */
public class TimetableConstraintProvider implements ConstraintProvider {

    /** The 4th pair of the shift and later are «late» (0-based position 3). */
    static final int FIRST_LATE_POSITION = 3;
    /** A discipline may appear at most twice a day before it is penalized. */
    static final int DISCIPLINE_PAIRS_PER_DAY = 2;

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
                brokenDays(factory),
                latePairs(factory),
                disciplineRepeat(factory),
                unwantedSlots(factory),
                roomPreference(factory),
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

    // ---- soft

    /** Free pairs between lessons of a student cohort in a numerator or denominator week. */
    Constraint groupWindows(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .groupBy(LessonUnit::getGroupId, LessonUnit::getWeekday, DayCollectors.cohortGaps())
                .filter((group, weekday, gaps) -> gaps > 0)
                .penalize(HardSoftScore.ONE_SOFT, (group, weekday, gaps) -> gaps)
                .asConstraint(GROUP_WINDOWS.id());
    }

    Constraint teacherWindows(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .filter(LessonUnit::hasTeacher)
                .groupBy(LessonUnit::getTeacherId, LessonUnit::getWeekday, DayCollectors.weekGaps())
                .filter((teacher, weekday, gaps) -> gaps > 0)
                .penalize(HardSoftScore.ONE_SOFT, (teacher, weekday, gaps) -> gaps)
                .asConstraint(TEACHER_WINDOWS.id());
    }

    /** «Рваний день»: the numerator and denominator weeks of a group-day differ in pairs or start. */
    Constraint brokenDays(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .groupBy(LessonUnit::getGroupId, LessonUnit::getWeekday, DayCollectors.weekBalance())
                .filter((group, weekday, balance) -> balance.isBroken())
                .penalize(HardSoftScore.ONE_SOFT, (group, weekday, balance) -> balance.brokenness())
                .asConstraint(BROKEN_DAY.id());
    }

    /** Lessons from the 4th pair of the shift on, the later the worse. */
    Constraint latePairs(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .filter(unit -> unit.positionInShift() >= FIRST_LATE_POSITION)
                .penalize(HardSoftScore.ONE_SOFT,
                        unit -> (unit.positionInShift() - FIRST_LATE_POSITION + 1) * unit.weeklyOccurrences())
                .asConstraint(LATE_PAIR.id());
    }

    /** The same discipline three or more times a day for a group (subgroups in one pair count once). */
    Constraint disciplineRepeat(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .groupBy(LessonUnit::getGroupId, LessonUnit::getWeekday, LessonUnit::getDisciplineId,
                        DayCollectors.weekBalance())
                .filter((group, weekday, discipline, balance) -> balance.excessOver(DISCIPLINE_PAIRS_PER_DAY) > 0)
                .penalize(HardSoftScore.ONE_SOFT,
                        (group, weekday, discipline, balance) -> balance.excessOver(DISCIPLINE_PAIRS_PER_DAY))
                .asConstraint(DISCIPLINE_REPEAT.id());
    }

    Constraint unwantedSlots(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .filter(LessonUnit::isTeacherUnwanted)
                .penalize(HardSoftScore.ONE_SOFT, LessonUnit::weeklyOccurrences)
                .asConstraint(UNWANTED_SLOT.id());
    }

    /** The lesson is not in its preferred room (load item) or, failing that, the group's home room. */
    Constraint roomPreference(ConstraintFactory factory) {
        return factory.forEach(LessonUnit.class)
                .filter(LessonUnit::isOutsideDesiredRoom)
                .penalize(HardSoftScore.ONE_SOFT, LessonUnit::weeklyOccurrences)
                .asConstraint(ROOM_PREFERENCE.id());
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

    private static long overflow(Room room, long lessons) {
        return Math.max(0, lessons - room.getMaxParallel());
    }
}
