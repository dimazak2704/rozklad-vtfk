package ua.vtc.rozklad.core.timetable.internal.metrics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.LatePairs;
import ua.vtc.rozklad.core.calendar.ShiftWindows.PairRange;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.settings.SystemSettings;
import ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFacts;
import ua.vtc.rozklad.core.timetable.internal.conflict.DayShape;
import ua.vtc.rozklad.core.timetable.internal.conflict.GroupFact;
import ua.vtc.rozklad.core.timetable.internal.conflict.SlotLesson;
import ua.vtc.rozklad.core.timetable.internal.conflict.WeekType;

/**
 * Metrics of a weekly template (used for versions and for generation «було / стало»). Uses the same
 * day shape as the soft conflict rules, so a removed warning is a better metric. Cohorts of a group are
 * the same all week (B4), late pairs follow {@link LatePairs}, and a teacher's wait between shifts is
 * counted apart from teacher windows ({@link DayShape#isShiftWait}). The hard conflict count is filled in
 * by the caller that ran the conflict engine.
 */
@Component
public class TimetableMetricsCalculator {

    private static final List<WeekType> WEEKS = List.of(WeekType.NUMERATOR, WeekType.DENOMINATOR);

    /** Metrics with the default teacher daily limit. */
    public MetricsDto calculate(Collection<SlotLesson> lessons, ConflictFacts facts) {
        return calculate(lessons, facts, SystemSettings.DEFAULT_TEACHER_MAX_PAIRS_PER_DAY);
    }

    /** @param teacherMaxPairsPerDay a teacher-day above it counts in {@code teacherOverloadDays} */
    public MetricsDto calculate(Collection<SlotLesson> lessons, ConflictFacts facts, int teacherMaxPairsPerDay) {
        List<SlotLesson> template = lessons.stream().filter(lesson -> !lesson.day().isDated()).toList();
        List<MetricsDto.GroupMetrics> perGroup = template.stream()
                .collect(Collectors.groupingBy(SlotLesson::groupId))
                .entrySet().stream()
                .map(entry -> groupMetrics(entry.getKey(), entry.getValue(), facts))
                .sorted(Comparator.comparingInt((MetricsDto.GroupMetrics row) -> row.group().course())
                        .thenComparing(row -> row.group().code()))
                .toList();
        TeacherCounts teachers = teacherCounts(template, facts, teacherMaxPairsPerDay);
        return new MetricsDto(
                perGroup.stream().mapToInt(MetricsDto.GroupMetrics::windows).sum(),
                teachers.windows,
                perGroup.stream().mapToInt(MetricsDto.GroupMetrics::brokenDays).sum(),
                perGroup.stream().mapToInt(MetricsDto.GroupMetrics::latePairs).sum(),
                perGroup.stream().mapToInt(MetricsDto.GroupMetrics::maxPairsPerDay).max().orElse(0),
                perGroup,
                null,
                teachers.shiftWaits,
                perGroup.stream().mapToInt(MetricsDto.GroupMetrics::onePairDays).sum(),
                perGroup.stream().mapToInt(MetricsDto.GroupMetrics::loneSubgroupSlots).sum(),
                teachers.days,
                teachers.onePairDays,
                teachers.overloadDays);
    }

    private static MetricsDto.GroupMetrics groupMetrics(UUID groupId, List<SlotLesson> lessons, ConflictFacts facts) {
        GroupFact group = facts.group(groupId);
        Set<Integer> weekSubgroups = DayShape.subgroupsByGroup(lessons).getOrDefault(groupId, Set.of());
        int windows = 0;
        int brokenDays = 0;
        int latePairs = 0;
        int maxPairs = 0;
        int onePairDays = 0;
        int loneSubgroupSlots = 0;
        for (List<SlotLesson> day : byWeekday(lessons).values()) {
            int weekday = day.getFirst().day().weekday();
            List<Integer> cohorts = DayShape.cohorts(day, group, weekSubgroups);
            for (WeekType week : WEEKS) {
                int worstWindows = 0;
                for (int cohort : cohorts) {
                    SortedSet<Integer> pairs = DayShape.pairs(DayShape.attended(day, cohort, week));
                    worstWindows = Math.max(worstWindows, DayShape.windowCount(pairs));
                    maxPairs = Math.max(maxPairs, pairs.size());
                }
                windows += worstWindows;
                List<SlotLesson> active = DayShape.active(day, week);
                SortedSet<Integer> occupied = DayShape.pairs(active);
                if (occupied.size() == 1) {
                    onePairDays++;
                }
                if (hasLatePair(group, weekday, occupied, facts)) {
                    latePairs++;
                }
                loneSubgroupSlots += loneSubgroupSlots(active, occupied, cohorts);
            }
            if (DayShape.isBroken(DayShape.pairs(DayShape.active(day, WeekType.NUMERATOR)),
                    DayShape.pairs(DayShape.active(day, WeekType.DENOMINATOR)))) {
                brokenDays++;
            }
        }
        GroupRef ref = group == null ? new GroupRef(groupId, "?", 0)
                : new GroupRef(group.id(), group.code(), group.course());
        return new MetricsDto.GroupMetrics(ref, windows, brokenDays, latePairs, maxPairs, onePairDays,
                loneSubgroupSlots);
    }

    /** A «4-та пара зміни» or later ({@link LatePairs}); Friday shift 2 (pairs 4–6) has none. */
    private static boolean hasLatePair(GroupFact group, int weekday, SortedSet<Integer> pairs, ConflictFacts facts) {
        if (group == null || pairs.isEmpty()) {
            return false;
        }
        return facts.shiftWindows().window(group.shift(), weekday)
                .map(PairRange::first)
                .filter(first -> pairs.stream().anyMatch(pair -> LatePairs.isLate(pair, first)))
                .isPresent();
    }

    /** Pairs where a subgroup has a lesson and another cohort of the group has none. */
    private static int loneSubgroupSlots(List<SlotLesson> active, SortedSet<Integer> occupied, List<Integer> cohorts) {
        int lone = 0;
        for (int pair : occupied) {
            List<SlotLesson> atPair = active.stream().filter(lesson -> lesson.pair() == pair).toList();
            boolean subgroupLesson = atPair.stream().anyMatch(lesson -> lesson.subgroup() > 0);
            boolean someCohortFree = cohorts.stream().anyMatch(cohort -> atPair.stream()
                    .noneMatch(lesson -> lesson.subgroup() == 0 || lesson.subgroup() == cohort));
            if (subgroupLesson && someCohortFree) {
                lone++;
            }
        }
        return lone;
    }

    private static TeacherCounts teacherCounts(List<SlotLesson> lessons, ConflictFacts facts, int maxPairsPerDay) {
        TeacherCounts counts = new TeacherCounts();
        Map<UUID, List<SlotLesson>> byTeacher = lessons.stream()
                .filter(lesson -> lesson.teacherId() != null)
                .collect(Collectors.groupingBy(SlotLesson::teacherId));
        for (List<SlotLesson> teacherLessons : byTeacher.values()) {
            for (List<SlotLesson> day : byWeekday(teacherLessons).values()) {
                for (WeekType week : WEEKS) {
                    List<SlotLesson> active = DayShape.active(day, week);
                    SortedSet<Integer> pairs = DayShape.pairs(active);
                    if (pairs.isEmpty()) {
                        continue;
                    }
                    counts.days++;
                    if (pairs.size() == 1) {
                        counts.onePairDays++;
                    }
                    if (pairs.size() > maxPairsPerDay) {
                        counts.overloadDays++;
                    }
                    for (DayShape.Gap gap : DayShape.gaps(pairs)) {
                        if (DayShape.isShiftWait(at(active, gap.first() - 1), at(active, gap.last() + 1), facts)) {
                            counts.shiftWaits += gap.length();
                        } else {
                            counts.windows += gap.length();
                        }
                    }
                }
            }
        }
        return counts;
    }

    private static List<SlotLesson> at(List<SlotLesson> lessons, int pair) {
        return lessons.stream().filter(lesson -> lesson.pair() == pair).toList();
    }

    private static Map<Integer, List<SlotLesson>> byWeekday(List<SlotLesson> lessons) {
        Map<Integer, List<SlotLesson>> byDay = new TreeMap<>();
        lessons.forEach(lesson -> byDay.computeIfAbsent(lesson.day().weekday(), day -> new ArrayList<>()).add(lesson));
        return byDay;
    }

    /** Teacher-day counts per kind of week. */
    private static final class TeacherCounts {

        private int windows;
        private int shiftWaits;
        private int days;
        private int onePairDays;
        private int overloadDays;
    }
}
