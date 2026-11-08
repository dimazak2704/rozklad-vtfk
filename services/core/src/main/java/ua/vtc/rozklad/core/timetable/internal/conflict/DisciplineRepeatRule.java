package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;
import ua.vtc.rozklad.core.shared.error.ConflictDto.Severity;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.shared.text.UkrainianText;

/** The same discipline three or more times a day for a group cohort. */
final class DisciplineRepeatRule implements ConflictRule {

    static final int REPEAT_THRESHOLD = 3;

    @Override
    public void check(LessonIndex index, ConflictFacts facts, ConflictSink sink) {
        index.byGroupDay().forEach((key, lessons) -> {
            LessonDay day = key.day();
            List<Integer> cohorts = DayShape.cohorts(lessons, facts.group(key.ownerId()));
            Map<Repeat, Found> found = new LinkedHashMap<>();
            for (WeekType week : day.weeks()) {
                for (int cohort : cohorts) {
                    LessonIndex.byOwner(DayShape.attended(lessons, cohort, week), SlotLesson::disciplineId)
                            .forEach((disciplineId, same) -> {
                                SortedSet<Integer> pairs = DayShape.pairs(same);
                                if (pairs.size() >= REPEAT_THRESHOLD) {
                                    int third = new ArrayList<>(pairs).get(REPEAT_THRESHOLD - 1);
                                    found.computeIfAbsent(new Repeat(disciplineId, pairs.size()),
                                            ignored -> new Found(third)).add(week, same);
                                }
                            });
                }
            }
            found.forEach((repeat, where) -> {
                String message = "%s стоїть %s за день у групи %s (%s%s)".formatted(
                        facts.disciplineName(repeat.disciplineId()), UkrainianText.times(repeat.count()),
                        facts.groupCode(key.ownerId()), ConflictText.day(day), ConflictText.weeks(day, where.weeks));
                sink.add(ConflictText.conflict(ConflictKind.DISCIPLINE_REPEAT, Severity.SOFT, message,
                        ConflictText.ids(where.lessons), day, where.thirdPair));
            });
        });
    }

    private record Repeat(UUID disciplineId, int count) {
    }

    private static final class Found {

        private final int thirdPair;
        private final Set<WeekType> weeks = EnumSet.noneOf(WeekType.class);
        private final Set<SlotLesson> lessons = new LinkedHashSet<>();

        private Found(int thirdPair) {
            this.thirdPair = thirdPair;
        }

        void add(WeekType week, List<SlotLesson> weekLessons) {
            weeks.add(week);
            lessons.addAll(weekLessons);
        }
    }
}
