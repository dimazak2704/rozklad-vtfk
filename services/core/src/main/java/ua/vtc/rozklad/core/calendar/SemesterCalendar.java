package ua.vtc.rozklad.core.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import ua.vtc.rozklad.contracts.Parity;

/**
 * Week numbering and parity of one semester (DOMAIN §2). Weeks are Monday-based; week 1 is the
 * week that contains {@code startsOn} (it may start before the semester: 01.09.2026 is a Tuesday,
 * so week 1 starts on Monday 31.08.2026). Odd weeks have {@code firstWeekParity}, even weeks the
 * other parity, and a manual override of a week wins. Verified: semester from 01.09.2026 ⇒
 * 19.10.2026 is week 8 ⇒ ЗНАМЕННИК, as printed in order №35.
 */
public final class SemesterCalendar {

    private final LocalDate startsOn;
    private final LocalDate endsOn;
    private final Parity firstWeekParity;
    private final Map<LocalDate, Parity> overrides;

    /**
     * @param overrides week start (a Monday) → parity
     */
    public SemesterCalendar(LocalDate startsOn, LocalDate endsOn, Parity firstWeekParity,
                            Map<LocalDate, Parity> overrides) {
        this.startsOn = Objects.requireNonNull(startsOn, "startsOn");
        this.endsOn = Objects.requireNonNull(endsOn, "endsOn");
        if (firstWeekParity == null || firstWeekParity == Parity.BOTH) {
            throw new IllegalArgumentException("first week parity must be NUMERATOR or DENOMINATOR");
        }
        this.firstWeekParity = firstWeekParity;
        this.overrides = Map.copyOf(overrides);
    }

    public static SemesterCalendar of(Semester semester, List<WeekParityOverride> overrides) {
        Map<LocalDate, Parity> byWeek = new java.util.HashMap<>();
        overrides.forEach(override -> byWeek.put(override.getWeekStart(), override.getParity()));
        return new SemesterCalendar(semester.getStartsOn(), semester.getEndsOn(), semester.getFirstWeekParity(), byWeek);
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(startsOn) && !date.isAfter(endsOn);
    }

    public LocalDate firstWeekStart() {
        return weekStartOf(startsOn);
    }

    /** Week number of any date on or after the first week start (1-based). */
    public int weekNumber(LocalDate date) {
        LocalDate weekStart = weekStartOf(date);
        if (weekStart.isBefore(firstWeekStart())) {
            throw new IllegalArgumentException(date + " is before the first week of the semester");
        }
        return (int) ChronoUnit.WEEKS.between(firstWeekStart(), weekStart) + 1;
    }

    /** Parity by the alternation rule only, ignoring overrides. */
    public Parity computedParity(int weekNumber) {
        if (weekNumber < 1) {
            throw new IllegalArgumentException("week number must be >= 1");
        }
        return weekNumber % 2 == 1 ? firstWeekParity : opposite(firstWeekParity);
    }

    /** Parity of the week of {@code date}: the override when there is one, the rule otherwise. */
    public Parity parity(LocalDate date) {
        Parity override = overrides.get(weekStartOf(date));
        return override != null ? override : computedParity(weekNumber(date));
    }

    public boolean isOverridden(LocalDate date) {
        return overrides.containsKey(weekStartOf(date));
    }

    /** Monday of every week from week 1 to the week of {@code endsOn}. */
    public List<Week> weeks() {
        List<Week> weeks = new ArrayList<>();
        LocalDate lastWeekStart = weekStartOf(endsOn);
        for (LocalDate monday = firstWeekStart(); !monday.isAfter(lastWeekStart); monday = monday.plusWeeks(1)) {
            int number = weekNumber(monday);
            weeks.add(new Week(number, monday, parity(monday), computedParity(number), overrides.containsKey(monday)));
        }
        return weeks;
    }

    /** Number of Monday-based weeks the semester spans. */
    public int calendarWeeks() {
        return weekNumber(endsOn);
    }

    public static LocalDate weekStartOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public static Parity opposite(Parity parity) {
        return switch (parity) {
            case NUMERATOR -> Parity.DENOMINATOR;
            case DENOMINATOR -> Parity.NUMERATOR;
            case BOTH -> throw new IllegalArgumentException("BOTH has no opposite");
        };
    }

    /**
     * @param parity         effective parity (override or rule)
     * @param computedParity parity by the rule alone
     */
    public record Week(int number, LocalDate weekStart, Parity parity, Parity computedParity, boolean overridden) {

        public LocalDate weekEnd() {
            return weekStart.plusDays(4);
        }
    }
}
