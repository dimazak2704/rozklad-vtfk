package ua.vtc.rozklad.core.timetable.internal.metrics;

import java.util.List;
import ua.vtc.rozklad.core.catalog.GroupRef;

/**
 * {@code MetricsDto} of CONTRACTS.md §2: quality of a weekly template. Windows and late pairs are
 * counted per kind of week (a BOTH lesson counts in the numerator and in the denominator week).
 *
 * @param groupWindows   free pairs between lessons of groups (the worst subgroup of a split group)
 * @param teacherWindows free pairs between lessons of teachers
 * @param brokenDays     group-days whose numerator and denominator weeks differ («рваний день»)
 * @param latePairs      group-days (per kind of week) that use the last pair of the group's shift window
 *                       (1st shift: 4th pair; 2nd shift: 8th, on Friday 6th)
 * @param maxPairsPerDay most pairs a cohort has in one day
 */
public record MetricsDto(int groupWindows, int teacherWindows, int brokenDays, int latePairs, int maxPairsPerDay,
                         List<GroupMetrics> perGroup) {

    public MetricsDto {
        perGroup = List.copyOf(perGroup);
    }

    public static MetricsDto empty() {
        return new MetricsDto(0, 0, 0, 0, 0, List.of());
    }

    public record GroupMetrics(GroupRef group, int windows, int brokenDays, int latePairs, int maxPairsPerDay) {
    }
}
