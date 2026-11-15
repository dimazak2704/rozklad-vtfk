package ua.vtc.rozklad.core.load.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.calendar.GroupTheory;

/**
 * Planned hours of a load item by the owner's rule of 15.11.2026 (pure): weeks the group is not on theory (НС,
 * practice, session, training camp, holidays — every DAY_OFF and SUSPEND period) do not count and are not caught up,
 * so the plan is the hours per week over the days the group really studies.
 *
 * <p>With the item's lessons in the published timetable the count is exact and keeps the parity: every weekly lesson
 * counts the group's study days on its weekday in the weeks of its parity (a numerator-only pair misses only the
 * numerator weeks spent off theory), which gives {@code T} pairs where the timetable has {@code C} pairs per two-week
 * cycle. The load wants {@code 2 × hoursPerWeek / hoursPerPair} pairs per cycle, so the plan is
 * {@code hoursPerPair × T × (2 × hoursPerWeek / hoursPerPair) / C = 2 × hoursPerWeek × T / C} hours: exactly the
 * timetable's pairs on theory days when it gives the load's hours, scaled when it gives more or fewer. A split item
 * takes its busiest subgroup (as accounting reports the cohort with the largest forecast).
 *
 * <p>Without lessons (no published timetable, or the discipline is not in it) the parity and the weekdays are
 * unknown: the plan is {@code hoursPerWeek × studyDays / 5}, a week counted by its study days.
 */
final class LoadPlan {

    private static final BigDecimal DAYS_PER_WEEK = BigDecimal.valueOf(5);

    private LoadPlan() {
    }

    static int plannedHours(BigDecimal hoursPerWeek, GroupTheory theory, List<Slot> slots) {
        Objects.requireNonNull(hoursPerWeek, "hoursPerWeek");
        Objects.requireNonNull(theory, "theory");
        if (slots.isEmpty()) {
            return hoursPerWeek.multiply(BigDecimal.valueOf(theory.studyDays()))
                    .divide(DAYS_PER_WEEK, 0, RoundingMode.HALF_UP).intValueExact();
        }
        Map<Integer, List<Slot>> cohorts = slots.stream()
                .collect(Collectors.groupingBy(Slot::subgroup, TreeMap::new, Collectors.toList()));
        return cohorts.values().stream()
                .mapToInt(cohort -> cohortHours(hoursPerWeek, theory, cohort))
                .max()
                .orElseThrow();
    }

    private static int cohortHours(BigDecimal hoursPerWeek, GroupTheory theory, List<Slot> cohort) {
        long pairsPerCycle = cohort.stream().mapToLong(slot -> slot.parity() == Parity.BOTH ? 2 : 1).sum();
        long theoryPairs = cohort.stream().mapToLong(slot -> theory.days(slot.weekday(), slot.parity())).sum();
        return hoursPerWeek.multiply(BigDecimal.valueOf(2 * theoryPairs))
                .divide(BigDecimal.valueOf(pairsPerCycle), 0, RoundingMode.HALF_UP).intValueExact();
    }

    /** One weekly lesson of the item: subgroup (0 = whole group), weekday 1..5 and parity. */
    record Slot(int subgroup, int weekday, Parity parity) {

        Slot {
            Objects.requireNonNull(parity, "parity");
        }
    }
}
