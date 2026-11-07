package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Lessons of one slot that clash pairwise in at least one kind of week (parities overlap: BOTH
 * meets everything, NUMERATOR never meets DENOMINATOR), with the weeks where they clash.
 */
record Clash(List<SlotLesson> lessons, Set<WeekType> weeks) {

    static Clash find(LessonDay day, List<SlotLesson> candidates, BiPredicate<SlotLesson, SlotLesson> clashes) {
        Set<SlotLesson> involved = new LinkedHashSet<>();
        Set<WeekType> weeks = EnumSet.noneOf(WeekType.class);
        for (WeekType week : day.weeks()) {
            List<SlotLesson> active = DayShape.active(candidates, week);
            for (int i = 0; i < active.size(); i++) {
                for (int j = i + 1; j < active.size(); j++) {
                    if (clashes.test(active.get(i), active.get(j))) {
                        involved.add(active.get(i));
                        involved.add(active.get(j));
                        weeks.add(week);
                    }
                }
            }
        }
        return new Clash(candidates.stream().filter(involved::contains).toList(), Collections.unmodifiableSet(weeks));
    }

    boolean isEmpty() {
        return lessons.isEmpty();
    }
}
