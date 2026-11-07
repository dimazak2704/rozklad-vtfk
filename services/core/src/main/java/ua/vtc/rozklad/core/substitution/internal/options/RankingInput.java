package ua.vtc.rozklad.core.substitution.internal.options;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.substitution.PreferenceKind;

/**
 * Everything {@link OptionRanker} needs about one lesson, already read from the database.
 *
 * @param teacherPairsThatDay  pairs every teacher has on the date (lessons and pending/confirmed assignments)
 * @param busyTeacherIds       teachers with a lesson or an assignment in the lesson's slot
 * @param absentTeacherIds     teachers absent in the lesson's slot
 * @param slotPreferences      UNWANTED / UNAVAILABLE of teachers for this weekday × pair
 * @param substitutionsThisMonth substitutions (pending, confirmed, applied) per teacher in the lesson's month
 * @param groupDay             other lessons of the group on the date
 * @param groupDisciplines     other disciplines of the group (load items) with the teacher of the lesson's cohort
 * @param makeUpSlots          free slots for a make-up lesson of the absent teacher within two weeks
 */
public record RankingInput(
        Target target,
        List<Candidate> teachers,
        Set<UUID> competentTeacherIds,
        Map<UUID, List<Integer>> teacherPairsThatDay,
        Set<UUID> busyTeacherIds,
        Set<UUID> absentTeacherIds,
        Map<UUID, PreferenceKind> slotPreferences,
        Map<UUID, Integer> substitutionsThisMonth,
        List<GroupLesson> groupDay,
        List<GroupDiscipline> groupDisciplines,
        List<MakeUpSlot> makeUpSlots) {

    public RankingInput {
        teachers = List.copyOf(teachers);
        competentTeacherIds = Set.copyOf(competentTeacherIds);
        teacherPairsThatDay = Map.copyOf(teacherPairsThatDay);
        busyTeacherIds = Set.copyOf(busyTeacherIds);
        absentTeacherIds = Set.copyOf(absentTeacherIds);
        slotPreferences = Map.copyOf(slotPreferences);
        substitutionsThisMonth = Map.copyOf(substitutionsThisMonth);
        groupDay = List.copyOf(groupDay);
        groupDisciplines = List.copyOf(groupDisciplines);
        makeUpSlots = List.copyOf(makeUpSlots);
    }

    /**
     * The lesson that needs a decision; {@code teacherId} is its (absent) teacher, may be {@code null};
     * {@code roomType} is the type of its room, may be {@code null}.
     */
    public record Target(UUID lessonId, LocalDate date, int pair, int subgroup, UUID disciplineId, UUID teacherId,
                         UUID teacherCommissionId, RoomType roomType) {
    }

    /** An active teacher who could take the pair. */
    public record Candidate(UUID id, String shortName, UUID cycleCommissionId) {
    }

    /** Another lesson of the group that day; {@code decided} = it already has a live decision. */
    public record GroupLesson(UUID lessonId, int pair, int subgroup, UUID disciplineId, String disciplineShortName,
                              UUID teacherId, String teacherShortName, boolean decided) {
    }

    /**
     * Another discipline of the group taught to the lesson's cohort by {@code teacherId};
     * {@code hoursBehind} = plan − forecast when positive.
     */
    public record GroupDiscipline(UUID disciplineId, String shortName, UUID teacherId, String teacherShortName,
                                  int hoursBehind, boolean finished, RoomType requiredRoomType) {
    }

    public record MakeUpSlot(LocalDate date, int pair) {
    }
}
