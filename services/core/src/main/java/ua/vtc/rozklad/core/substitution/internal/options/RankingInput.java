package ua.vtc.rozklad.core.substitution.internal.options;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.accounting.AccountingStatus;
import ua.vtc.rozklad.core.substitution.PreferenceKind;

/**
 * Everything {@link OptionRanker} needs about one lesson, already read from the database
 * ({@link OptionsGatherer}): a pure value, so the ranking is a function of it and can be replayed
 * from JSON (DOMAIN §7).
 *
 * @param groupDay            other SCHEDULED lessons of the group that day
 * @param groupDisciplines    the pool: other disciplines of the group for the lesson's cohort (whole group ↔
 *                            unsplit items, subgroup k ↔ the teacher of k), one entry per discipline and teacher
 * @param teacherPairsThatDay pairs every teacher has that day: lessons and open assignments, without lessons an
 *                            open change takes away
 * @param busyByPair          teachers busy at a pair (lesson or open assignment); freed lessons and the stream of
 *                            the target are already left out
 * @param absentByPair        teachers absent at a pair
 * @param slotPreferences     UNWANTED / UNAVAILABLE of teachers for the lesson's weekday × pair
 * @param weeklyHours         teaching load of the pool's teachers, Σ hours per week of their load items
 * @param coverBurden         weighted cover pairs of the last 6 weeks (1.0 up to 14 days old, 0.5 up to 42,
 *                            1.0 for an open one on the same date)
 * @param coverCount          the same cover pairs, counted
 * @param lastCover           date of the latest of those cover pairs
 * @param declinedThisLesson  teachers who declined a request for this lesson
 * @param declinedToday       teachers who declined a request for another lesson that day
 * @param planPenalty         extra cost of a teacher the day plan has already proposed for another pair
 */
public record RankingInput(
        Target target,
        List<GroupLesson> groupDay,
        List<GroupDiscipline> groupDisciplines,
        Map<UUID, List<Integer>> teacherPairsThatDay,
        Map<Integer, Set<UUID>> busyByPair,
        Map<Integer, Set<UUID>> absentByPair,
        Map<UUID, PreferenceKind> slotPreferences,
        Map<UUID, BigDecimal> weeklyHours,
        Map<UUID, Double> coverBurden,
        Map<UUID, Integer> coverCount,
        Map<UUID, LocalDate> lastCover,
        Set<UUID> declinedThisLesson,
        Set<UUID> declinedToday,
        Map<UUID, Integer> planPenalty) {

    public RankingInput {
        groupDay = List.copyOf(groupDay);
        groupDisciplines = List.copyOf(groupDisciplines);
        teacherPairsThatDay = Map.copyOf(teacherPairsThatDay);
        busyByPair = Map.copyOf(busyByPair);
        absentByPair = Map.copyOf(absentByPair);
        slotPreferences = Map.copyOf(slotPreferences);
        weeklyHours = Map.copyOf(weeklyHours);
        coverBurden = Map.copyOf(coverBurden);
        coverCount = Map.copyOf(coverCount);
        lastCover = Map.copyOf(lastCover);
        declinedThisLesson = Set.copyOf(declinedThisLesson);
        declinedToday = Set.copyOf(declinedToday);
        planPenalty = planPenalty == null ? Map.of() : Map.copyOf(planPenalty);
    }

    public boolean busy(UUID teacherId, int pair) {
        return busyByPair.getOrDefault(pair, Set.of()).contains(teacherId);
    }

    public boolean absent(UUID teacherId, int pair) {
        return absentByPair.getOrDefault(pair, Set.of()).contains(teacherId);
    }

    /**
     * The lesson that needs a decision. {@code teacherId} is its (absent) teacher, may be
     * {@code null}; {@code roomType} the type of its room; {@code today} the dispatcher's date (a
     * lesson before it is past); {@code groupHasBlock} the group has a block that day;
     * {@code progress} plan / fact / forecast of its discipline, {@code null} without a load item.
     */
    public record Target(UUID lessonId, LocalDate date, LocalDate today, int pair, int subgroup, UUID disciplineId,
                         String disciplineShortName, UUID teacherId, RoomType roomType, UUID streamId, boolean online,
                         int hoursPerPair, boolean groupHasBlock, Progress progress) {

        public boolean past() {
            return date.isBefore(today);
        }
    }

    /** Another lesson of the group that day; {@code decided} = it already has a live decision. */
    public record GroupLesson(UUID lessonId, int pair, int subgroup, UUID disciplineId, String disciplineShortName,
                              UUID teacherId, String teacherShortName, UUID roomId, boolean decided) {
    }

    /**
     * A discipline of the pool taught to the lesson's cohort by {@code teacherId};
     * {@code ownRoomId} is the room where this teacher usually has it with the group, when that room
     * is free at the pair.
     */
    public record GroupDiscipline(UUID disciplineId, String shortName, UUID teacherId, String teacherShortName,
                                  Progress progress, RoomType requiredRoomType, UUID ownRoomId) {
    }

    /** Plan / fact / forecast in academic hours (DOMAIN §9) and the load's hours per week. */
    public record Progress(int plan, int fact, int forecast, AccountingStatus status, BigDecimal hoursPerWeek) {

        /** Hours the scheduled lessons will not cover; 0 when on track. */
        public int hoursBehind() {
            return Math.max(0, plan - forecast);
        }

        /** (plan − forecast) / plan; 0 without a plan. */
        public double relativeLag() {
            return plan <= 0 ? 0 : (plan - forecast) / (double) plan;
        }
    }
}
