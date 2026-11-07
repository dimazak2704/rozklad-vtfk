package ua.vtc.rozklad.core.timetable.internal.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.Objects;
import ua.vtc.rozklad.contracts.Parity;

/**
 * Week parity of DOMAIN.md §2: weeks are Monday-based, week 1 contains the first day of the
 * semester, odd weeks have {@code firstWeekParity}, even weeks the other one; a manual override of a
 * week wins. Verified: start 01.09.2026 (Tue) ⇒ the week of 19.10.2026 is week 8 ⇒ ЗНАМЕННИК.
 */
public final class WeekParityCalculator {

    private final LocalDate firstMonday;
    private final Parity firstWeekParity;
    private final Map<LocalDate, Parity> overridesByMonday;

    /** @param overridesByMonday week start (a Monday) → NUMERATOR or DENOMINATOR */
    public WeekParityCalculator(LocalDate semesterStart, Parity firstWeekParity, Map<LocalDate, Parity> overridesByMonday) {
        Objects.requireNonNull(semesterStart, "semesterStart");
        if (firstWeekParity == null || firstWeekParity == Parity.BOTH) {
            throw new IllegalArgumentException("first week parity must be NUMERATOR or DENOMINATOR");
        }
        this.firstMonday = mondayOf(semesterStart);
        this.firstWeekParity = firstWeekParity;
        this.overridesByMonday = Map.copyOf(overridesByMonday);
    }

    public static LocalDate mondayOf(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** 1 for the week of the semester start; may be 0 or negative before it. */
    public int weekNumber(LocalDate date) {
        return (int) ChronoUnit.WEEKS.between(firstMonday, mondayOf(date)) + 1;
    }

    /** NUMERATOR or DENOMINATOR, never BOTH. */
    public Parity parity(LocalDate date) {
        Parity override = overridesByMonday.get(mondayOf(date));
        if (override != null) {
            return override;
        }
        return Math.floorMod(weekNumber(date), 2) == 1 ? firstWeekParity : opposite(firstWeekParity);
    }

    private static Parity opposite(Parity parity) {
        return parity == Parity.NUMERATOR ? Parity.DENOMINATOR : Parity.NUMERATOR;
    }
}
