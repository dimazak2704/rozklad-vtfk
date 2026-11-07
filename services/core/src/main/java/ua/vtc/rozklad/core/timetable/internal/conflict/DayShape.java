package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Shape of a school day shared by the soft rules and the metrics: cohorts of a group, occupied pairs
 * per week, windows (free pairs between the first and the last one) and «рваний день».
 */
public final class DayShape {

    private DayShape() {
    }

    /**
     * Cohorts whose days are evaluated: the stored subgroups plus every subgroup that has a lesson
     * that day; {@code [0]} (the whole group) when the group is not split.
     */
    public static List<Integer> cohorts(Collection<SlotLesson> groupDayLessons, GroupFact group) {
        SortedSet<Integer> cohorts = new TreeSet<>();
        if (group != null) {
            cohorts.addAll(group.subgroups());
        }
        groupDayLessons.stream().map(SlotLesson::subgroup).filter(subgroup -> subgroup > 0).forEach(cohorts::add);
        return cohorts.isEmpty() ? List.of(0) : List.copyOf(cohorts);
    }

    /** Lessons a cohort attends in a week: whole-group lessons and its own subgroup lessons. */
    public static List<SlotLesson> attended(Collection<SlotLesson> groupDayLessons, int cohort, WeekType week) {
        return groupDayLessons.stream()
                .filter(lesson -> lesson.activeIn(week))
                .filter(lesson -> lesson.subgroup() == 0 || lesson.subgroup() == cohort)
                .toList();
    }

    public static List<SlotLesson> active(Collection<SlotLesson> lessons, WeekType week) {
        return lessons.stream().filter(lesson -> lesson.activeIn(week)).toList();
    }

    public static SortedSet<Integer> pairs(Collection<SlotLesson> lessons) {
        SortedSet<Integer> pairs = new TreeSet<>();
        lessons.forEach(lesson -> pairs.add(lesson.pair()));
        return pairs;
    }

    /** Maximal runs of free pairs strictly between the first and the last occupied pair. */
    public static List<Gap> gaps(SortedSet<Integer> pairs) {
        List<Gap> gaps = new ArrayList<>();
        Integer previous = null;
        for (int pair : pairs) {
            if (previous != null && pair > previous + 1) {
                gaps.add(new Gap(previous + 1, pair - 1));
            }
            previous = pair;
        }
        return gaps;
    }

    /** Number of free pairs inside the day (windows). */
    public static int windowCount(SortedSet<Integer> pairs) {
        return gaps(pairs).stream().mapToInt(Gap::length).sum();
    }

    /**
     * «Рваний день» (DOMAIN.md §5): in numerator and denominator weeks the group has a different
     * number of pairs or starts at a different pair.
     */
    public static boolean isBroken(SortedSet<Integer> numerator, SortedSet<Integer> denominator) {
        if (numerator.isEmpty() && denominator.isEmpty()) {
            return false;
        }
        if (numerator.size() != denominator.size()) {
            return true;
        }
        return !numerator.first().equals(denominator.first());
    }

    /** First pair that the group has in one kind of week only. */
    public static int firstDifference(SortedSet<Integer> numerator, SortedSet<Integer> denominator) {
        for (int pair = 1; pair <= 8; pair++) {
            if (numerator.contains(pair) != denominator.contains(pair)) {
                return pair;
            }
        }
        return numerator.isEmpty() ? denominator.first() : numerator.first();
    }

    /** Free pairs {@code first..last} inside a day. */
    public record Gap(int first, int last) {

        public int length() {
            return last - first + 1;
        }
    }
}
