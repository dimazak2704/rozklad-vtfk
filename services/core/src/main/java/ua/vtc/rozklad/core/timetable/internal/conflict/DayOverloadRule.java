package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import ua.vtc.rozklad.core.shared.error.ConflictDto.Severity;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.shared.text.UkrainianText;

/** More than four pairs a day for a group cohort (the busiest subgroup counts). */
final class DayOverloadRule implements ConflictRule {

    static final int MAX_PAIRS_PER_DAY = 4;

    @Override
    public void check(LessonIndex index, ConflictFacts facts, ConflictSink sink) {
        index.byGroupDay().forEach((key, lessons) -> {
            LessonDay day = key.day();
            List<Integer> cohorts = DayShape.cohorts(lessons, facts.group(key.ownerId()));
            Map<Integer, Overload> byCount = new LinkedHashMap<>();
            for (WeekType week : day.weeks()) {
                List<SlotLesson> busiest = List.of();
                for (int cohort : cohorts) {
                    List<SlotLesson> attended = DayShape.attended(lessons, cohort, week);
                    if (DayShape.pairs(attended).size() > DayShape.pairs(busiest).size()) {
                        busiest = attended;
                    }
                }
                SortedSet<Integer> pairs = DayShape.pairs(busiest);
                if (pairs.size() > MAX_PAIRS_PER_DAY) {
                    int firstExtra = new ArrayList<>(pairs).get(MAX_PAIRS_PER_DAY);
                    byCount.computeIfAbsent(pairs.size(), ignored -> new Overload(firstExtra)).add(week, busiest);
                }
            }
            byCount.forEach((count, overload) -> {
                String message = "Група %s має %s за день (%s%s)".formatted(facts.groupCode(key.ownerId()),
                        UkrainianText.pairs(count), ConflictText.day(day), ConflictText.weeks(day, overload.weeks));
                sink.add(ConflictText.conflict(ConflictKind.DAY_OVERLOAD, Severity.SOFT, message,
                        ConflictText.ids(overload.lessons), day, overload.firstExtraPair));
            });
        });
    }

    private static final class Overload {

        private final int firstExtraPair;
        private final Set<WeekType> weeks = EnumSet.noneOf(WeekType.class);
        private final Set<SlotLesson> lessons = new LinkedHashSet<>();

        private Overload(int firstExtraPair) {
            this.firstExtraPair = firstExtraPair;
        }

        void add(WeekType week, List<SlotLesson> weekLessons) {
            weeks.add(week);
            lessons.addAll(weekLessons);
        }
    }
}
