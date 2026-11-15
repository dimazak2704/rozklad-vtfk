package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ua.vtc.rozklad.core.shared.error.ConflictDto.Severity;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.timetable.internal.conflict.DayShape.Gap;

/**
 * «Вікно» of a teacher: a free pair between their lessons of a day, per kind of week. A wait between a
 * lesson of a 1st-shift group and one of a 2nd-shift group is not a window ({@link DayShape#isShiftWait}),
 * as in the metrics.
 */
final class TeacherWindowRule implements ConflictRule {

    @Override
    public void check(LessonIndex index, ConflictFacts facts, ConflictSink sink) {
        index.byTeacherDay().forEach((key, lessons) -> {
            LessonDay day = key.day();
            Map<Gap, Set<WeekType>> gaps = new LinkedHashMap<>();
            for (WeekType week : day.weeks()) {
                List<SlotLesson> active = DayShape.active(lessons, week);
                for (Gap gap : DayShape.gaps(DayShape.pairs(active))) {
                    if (!DayShape.isShiftWait(at(active, gap.first() - 1), at(active, gap.last() + 1), facts)) {
                        gaps.computeIfAbsent(gap, ignored -> EnumSet.noneOf(WeekType.class)).add(week);
                    }
                }
            }
            gaps.forEach((gap, weeks) -> {
                List<SlotLesson> around = lessons.stream()
                        .filter(lesson -> lesson.pair() == gap.first() - 1 || lesson.pair() == gap.last() + 1)
                        .filter(lesson -> weeks.stream().anyMatch(lesson::activeIn))
                        .toList();
                String message = "Вікно у викладача %s між %d і %d парою (%s%s)".formatted(
                        facts.teacherName(key.ownerId()), gap.first() - 1, gap.last() + 1, ConflictText.day(day),
                        ConflictText.weeks(day, weeks));
                sink.add(ConflictText.conflict(ConflictKind.WINDOW, Severity.SOFT, message, ConflictText.ids(around),
                        day, gap.first()));
            });
        });
    }

    private static List<SlotLesson> at(List<SlotLesson> lessons, int pair) {
        return lessons.stream().filter(lesson -> lesson.pair() == pair).toList();
    }
}
