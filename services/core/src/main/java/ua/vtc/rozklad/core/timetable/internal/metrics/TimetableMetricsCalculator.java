package ua.vtc.rozklad.core.timetable.internal.metrics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.timetable.GroupRef;
import ua.vtc.rozklad.core.timetable.internal.calendar.ShiftWindows.PairRange;
import ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFacts;
import ua.vtc.rozklad.core.timetable.internal.conflict.DayShape;
import ua.vtc.rozklad.core.timetable.internal.conflict.GroupFact;
import ua.vtc.rozklad.core.timetable.internal.conflict.SlotLesson;
import ua.vtc.rozklad.core.timetable.internal.conflict.WeekType;

/**
 * Metrics of a weekly template (used for versions and for generation «було / стало»). Uses the same
 * day shape as the soft conflict rules, so a removed warning is a better metric.
 */
@Component
public class TimetableMetricsCalculator {

    private static final List<WeekType> WEEKS = List.of(WeekType.NUMERATOR, WeekType.DENOMINATOR);

    public MetricsDto calculate(Collection<SlotLesson> lessons, ConflictFacts facts) {
        List<SlotLesson> template = lessons.stream().filter(lesson -> !lesson.day().isDated()).toList();
        List<MetricsDto.GroupMetrics> perGroup = template.stream()
                .collect(Collectors.groupingBy(SlotLesson::groupId))
                .entrySet().stream()
                .map(entry -> groupMetrics(entry.getKey(), entry.getValue(), facts))
                .sorted(Comparator.comparingInt((MetricsDto.GroupMetrics row) -> row.group().course())
                        .thenComparing(row -> row.group().code()))
                .toList();
        return new MetricsDto(
                perGroup.stream().mapToInt(MetricsDto.GroupMetrics::windows).sum(),
                teacherWindows(template),
                perGroup.stream().mapToInt(MetricsDto.GroupMetrics::brokenDays).sum(),
                perGroup.stream().mapToInt(MetricsDto.GroupMetrics::latePairs).sum(),
                perGroup.stream().mapToInt(MetricsDto.GroupMetrics::maxPairsPerDay).max().orElse(0),
                perGroup);
    }

    private static MetricsDto.GroupMetrics groupMetrics(UUID groupId, List<SlotLesson> lessons, ConflictFacts facts) {
        GroupFact group = facts.group(groupId);
        int windows = 0;
        int brokenDays = 0;
        int latePairs = 0;
        int maxPairs = 0;
        for (List<SlotLesson> day : byWeekday(lessons).values()) {
            int weekday = day.getFirst().day().weekday();
            List<Integer> cohorts = DayShape.cohorts(day, group);
            for (WeekType week : WEEKS) {
                int worstWindows = 0;
                for (int cohort : cohorts) {
                    SortedSet<Integer> pairs = DayShape.pairs(DayShape.attended(day, cohort, week));
                    worstWindows = Math.max(worstWindows, DayShape.windowCount(pairs));
                    maxPairs = Math.max(maxPairs, pairs.size());
                }
                windows += worstWindows;
                if (usesLastPair(group, weekday, DayShape.pairs(DayShape.active(day, week)), facts)) {
                    latePairs++;
                }
            }
            if (DayShape.isBroken(DayShape.pairs(DayShape.active(day, WeekType.NUMERATOR)),
                    DayShape.pairs(DayShape.active(day, WeekType.DENOMINATOR)))) {
                brokenDays++;
            }
        }
        GroupRef ref = group == null ? new GroupRef(groupId, "?", 0)
                : new GroupRef(group.id(), group.code(), group.course());
        return new MetricsDto.GroupMetrics(ref, windows, brokenDays, latePairs, maxPairs);
    }

    /** The last pair of the shift window counts as a late pair (shift 1: 4th; shift 2: 8th, Friday 6th). */
    private static boolean usesLastPair(GroupFact group, int weekday, SortedSet<Integer> pairs, ConflictFacts facts) {
        if (group == null || pairs.isEmpty()) {
            return false;
        }
        return facts.shiftWindows().window(group.shift(), weekday)
                .map(PairRange::last)
                .filter(pairs::contains)
                .isPresent();
    }

    private static int teacherWindows(List<SlotLesson> lessons) {
        int windows = 0;
        Map<UUID, List<SlotLesson>> byTeacher = lessons.stream()
                .filter(lesson -> lesson.teacherId() != null)
                .collect(Collectors.groupingBy(SlotLesson::teacherId));
        for (List<SlotLesson> teacherLessons : byTeacher.values()) {
            for (List<SlotLesson> day : byWeekday(teacherLessons).values()) {
                for (WeekType week : WEEKS) {
                    windows += DayShape.windowCount(DayShape.pairs(DayShape.active(day, week)));
                }
            }
        }
        return windows;
    }

    private static Map<Integer, List<SlotLesson>> byWeekday(List<SlotLesson> lessons) {
        Map<Integer, List<SlotLesson>> byDay = new TreeMap<>();
        lessons.forEach(lesson -> byDay.computeIfAbsent(lesson.day().weekday(), day -> new ArrayList<>()).add(lesson));
        return byDay;
    }
}
