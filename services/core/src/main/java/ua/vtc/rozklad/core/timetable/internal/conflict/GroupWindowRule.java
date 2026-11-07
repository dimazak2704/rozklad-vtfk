package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import ua.vtc.rozklad.core.shared.error.ConflictDto.Severity;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.timetable.internal.conflict.DayShape.Gap;

/**
 * «Вікно» of a group: a free pair between lessons of a day, per kind of week. A split group is
 * checked per subgroup (whole-group lessons + own lessons); a window every cohort has is reported
 * once for the group.
 */
final class GroupWindowRule implements ConflictRule {

    @Override
    public void check(LessonIndex index, ConflictFacts facts, ConflictSink sink) {
        index.byGroupDay().forEach((key, lessons) -> {
            LessonDay day = key.day();
            List<Integer> cohorts = DayShape.cohorts(lessons, facts.group(key.ownerId()));
            Map<Gap, Map<WeekType, SortedSet<Integer>>> cohortsWithGap = new LinkedHashMap<>();
            for (WeekType week : day.weeks()) {
                for (int cohort : cohorts) {
                    for (Gap gap : DayShape.gaps(DayShape.pairs(DayShape.attended(lessons, cohort, week)))) {
                        cohortsWithGap.computeIfAbsent(gap, ignored -> new EnumMap<>(WeekType.class))
                                .computeIfAbsent(week, ignored -> new TreeSet<>())
                                .add(cohort);
                    }
                }
            }
            cohortsWithGap.forEach((gap, byWeek) -> report(key, gap, byWeek, cohorts, lessons, facts, sink));
        });
    }

    private static void report(LessonIndex.DayKey key, Gap gap, Map<WeekType, SortedSet<Integer>> byWeek,
                               List<Integer> cohorts, List<SlotLesson> lessons, ConflictFacts facts,
                               ConflictSink sink) {
        Set<WeekType> wholeGroupWeeks = EnumSet.noneOf(WeekType.class);
        Map<Integer, Set<WeekType>> cohortWeeks = new LinkedHashMap<>();
        byWeek.forEach((week, withGap) -> {
            if (withGap.size() == cohorts.size()) {
                wholeGroupWeeks.add(week);
            } else {
                withGap.forEach(cohort -> cohortWeeks.computeIfAbsent(cohort, ignored -> EnumSet.noneOf(WeekType.class))
                        .add(week));
            }
        });
        if (!wholeGroupWeeks.isEmpty()) {
            add(key, gap, wholeGroupWeeks, null, lessons, facts, sink);
        }
        cohortWeeks.forEach((cohort, weeks) -> add(key, gap, weeks, cohort, lessons, facts, sink));
    }

    private static void add(LessonIndex.DayKey key, Gap gap, Set<WeekType> weeks, Integer cohort,
                            List<SlotLesson> lessons, ConflictFacts facts, ConflictSink sink) {
        LessonDay day = key.day();
        List<SlotLesson> around = lessons.stream()
                .filter(lesson -> lesson.pair() == gap.first() - 1 || lesson.pair() == gap.last() + 1)
                .filter(lesson -> weeks.stream().anyMatch(lesson::activeIn))
                .filter(lesson -> cohort == null || lesson.subgroup() == 0 || lesson.subgroup() == cohort)
                .toList();
        String owner = facts.groupCode(key.ownerId()) + (cohort == null || cohort == 0 ? "" : ConflictText.subgroup(cohort));
        String message = "Вікно у групи %s між %d і %d парою (%s%s)".formatted(owner, gap.first() - 1,
                gap.last() + 1, ConflictText.day(day), ConflictText.weeks(day, weeks));
        sink.add(ConflictText.conflict(ConflictKind.WINDOW, Severity.SOFT, message, ConflictText.ids(around), day,
                gap.first()));
    }
}
