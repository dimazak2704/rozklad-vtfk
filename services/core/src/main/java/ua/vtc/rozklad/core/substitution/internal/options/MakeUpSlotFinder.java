package ua.vtc.rozklad.core.substitution.internal.options;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.MakeUpSlot;

/**
 * Free slots for a make-up lesson (відпрацювання, DOMAIN §7) as a pure function: on a study day
 * when the group is at college anyway, the pair right after its last pair (or right before its
 * first one) inside the shift window, free for the group and for the teacher. At most one slot per
 * day, earliest days first, so the group gets no window and no extra trip.
 */
public final class MakeUpSlotFinder {

    private MakeUpSlotFinder() {
    }

    /**
     * @param groupPairs      pairs occupied by the group's cohort that day
     * @param teacherBusyPairs pairs when the teacher teaches, is assigned or is absent
     */
    public record Day(LocalDate date, int firstPair, int lastPair, Set<Integer> groupPairs,
                      Set<Integer> teacherBusyPairs) {

        public Day {
            groupPairs = Set.copyOf(groupPairs);
            teacherBusyPairs = Set.copyOf(teacherBusyPairs);
        }
    }

    public static List<MakeUpSlot> find(List<Day> days, int limit) {
        List<MakeUpSlot> slots = new ArrayList<>();
        for (Day day : days.stream().sorted(Comparator.comparing(Day::date)).toList()) {
            if (slots.size() >= limit) {
                break;
            }
            if (day.groupPairs().isEmpty()) {
                continue;
            }
            int last = day.groupPairs().stream().mapToInt(Integer::intValue).max().orElseThrow();
            int first = day.groupPairs().stream().mapToInt(Integer::intValue).min().orElseThrow();
            for (int candidate : List.of(last + 1, first - 1)) {
                if (fits(day, candidate)) {
                    slots.add(new MakeUpSlot(day.date(), candidate));
                    break;
                }
            }
        }
        return slots;
    }

    private static boolean fits(Day day, int pair) {
        return pair >= day.firstPair() && pair <= day.lastPair()
                && !day.groupPairs().contains(pair) && !day.teacherBusyPairs().contains(pair);
    }
}
