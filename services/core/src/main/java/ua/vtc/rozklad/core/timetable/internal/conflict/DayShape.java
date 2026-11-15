package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;

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
        return cohorts(groupDayLessons, group, Set.of());
    }

    /**
     * As {@link #cohorts(Collection, GroupFact)} plus the subgroups the group uses on any day of the checked
     * lessons ({@code weekSubgroups}). B4: the catalog often stores no subgroups, and a subgroup without a
     * lesson today still sits through today's whole-group pairs, so its windows count.
     */
    public static List<Integer> cohorts(Collection<SlotLesson> groupDayLessons, GroupFact group,
                                        Set<Integer> weekSubgroups) {
        SortedSet<Integer> cohorts = new TreeSet<>();
        if (group != null) {
            cohorts.addAll(group.subgroups());
        }
        weekSubgroups.stream().filter(subgroup -> subgroup > 0).forEach(cohorts::add);
        groupDayLessons.stream().map(SlotLesson::subgroup).filter(subgroup -> subgroup > 0).forEach(cohorts::add);
        return cohorts.isEmpty() ? List.of(0) : List.copyOf(cohorts);
    }

    /** Subgroups (above 0) used in any of the lessons, per group. */
    public static Map<UUID, Set<Integer>> subgroupsByGroup(Collection<SlotLesson> lessons) {
        Map<UUID, Set<Integer>> subgroups = new HashMap<>();
        lessons.stream().filter(lesson -> lesson.subgroup() > 0).forEach(lesson -> subgroups
                .computeIfAbsent(lesson.groupId(), id -> new TreeSet<>()).add(lesson.subgroup()));
        return subgroups;
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

    /**
     * A teacher's gap between consecutive occupied pairs is a wait between shifts, not a window, when every
     * lesson before it is of a group of one shift and every lesson after it of a group of the other shift
     * (one definition with the scheduler's «Очікування викладача між змінами»). In the real timetable 186 of
     * the 309 teacher gap pairs are such waits.
     *
     * @param before the teacher's lessons at the pair before the gap (one kind of week)
     * @param after  the teacher's lessons at the pair after it
     */
    public static boolean isShiftWait(Collection<SlotLesson> before, Collection<SlotLesson> after,
                                      ConflictFacts facts) {
        Integer shiftBefore = singleShift(before, facts);
        Integer shiftAfter = singleShift(after, facts);
        return shiftBefore != null && shiftAfter != null && !shiftBefore.equals(shiftAfter);
    }

    /** The shift all the lessons' groups share, or {@code null} (unknown group, mixed shifts, no lessons). */
    private static Integer singleShift(Collection<SlotLesson> lessons, ConflictFacts facts) {
        Integer shift = null;
        for (SlotLesson lesson : lessons) {
            GroupFact group = facts.group(lesson.groupId());
            if (group == null || (shift != null && shift != group.shift())) {
                return null;
            }
            shift = group.shift();
        }
        return shift;
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
