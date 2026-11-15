package ua.vtc.rozklad.core.timetable.internal.metrics;

import java.util.List;
import ua.vtc.rozklad.core.catalog.GroupRef;

/**
 * {@code MetricsDto} of CONTRACTS.md §2: quality of a weekly template. Everything is counted per kind of
 * week (a BOTH lesson counts in the numerator and in the denominator week). The fields after
 * {@code perGroup} are additive (genfix): {@code null} in metrics stored before them.
 *
 * @param groupWindows        free pairs between lessons of groups (the worst cohort of a group-day)
 * @param teacherWindows      free pairs between lessons of teachers, except waits between shifts
 * @param brokenDays          group-days whose numerator and denominator weeks differ («рваний день»)
 * @param latePairs           group-days that use a «4-та пара зміни» ({@code contracts.LatePairs}: shift 1 the
 *                            4th pair, shift 2 the 8th; Friday shift 2 has none)
 * @param maxPairsPerDay      most pairs a cohort has in one day
 * @param hardConflicts       hard conflicts of the template (the conflict engine's count)
 * @param teacherShiftWaits   free pairs of a teacher between a lesson of a 1st-shift group and one of a
 *                            2nd-shift group (not counted in {@code teacherWindows})
 * @param studentOnePairDays  group-days with exactly one occupied pair (any cohort)
 * @param loneSubgroupSlots   group slots where a subgroup has a lesson and another cohort of the group has none
 * @param teacherDays         teacher-days with at least one pair
 * @param teacherOnePairDays  teacher-days with exactly one pair
 * @param teacherOverloadDays teacher-days with more pairs than the setting {@code teacherMaxPairsPerDay}
 */
public record MetricsDto(int groupWindows, int teacherWindows, int brokenDays, int latePairs, int maxPairsPerDay,
                         List<GroupMetrics> perGroup, Integer hardConflicts, Integer teacherShiftWaits,
                         Integer studentOnePairDays, Integer loneSubgroupSlots, Integer teacherDays,
                         Integer teacherOnePairDays, Integer teacherOverloadDays) {

    public MetricsDto {
        perGroup = List.copyOf(perGroup);
    }

    /** Metrics of the first schema (stored before the genfix fields). */
    public MetricsDto(int groupWindows, int teacherWindows, int brokenDays, int latePairs, int maxPairsPerDay,
                      List<GroupMetrics> perGroup) {
        this(groupWindows, teacherWindows, brokenDays, latePairs, maxPairsPerDay, perGroup, null, null, null, null,
                null, null, null);
    }

    public static MetricsDto empty() {
        return new MetricsDto(0, 0, 0, 0, 0, List.of(), 0, 0, 0, 0, 0, 0, 0);
    }

    /** The same metrics with the template's hard conflict count. */
    public MetricsDto withHardConflicts(int count) {
        return new MetricsDto(groupWindows, teacherWindows, brokenDays, latePairs, maxPairsPerDay, perGroup, count,
                teacherShiftWaits, studentOnePairDays, loneSubgroupSlots, teacherDays, teacherOnePairDays,
                teacherOverloadDays);
    }

    /**
     * Per-group metrics; {@code onePairDays} and {@code loneSubgroupSlots} are additive ({@code null} in metrics
     * stored before them).
     */
    public record GroupMetrics(GroupRef group, int windows, int brokenDays, int latePairs, int maxPairsPerDay,
                               Integer onePairDays, Integer loneSubgroupSlots) {

        /** A row of the first schema. */
        public GroupMetrics(GroupRef group, int windows, int brokenDays, int latePairs, int maxPairsPerDay) {
            this(group, windows, brokenDays, latePairs, maxPairsPerDay, null, null);
        }
    }
}
