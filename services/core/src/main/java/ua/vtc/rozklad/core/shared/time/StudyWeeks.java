package ua.vtc.rozklad.core.shared.time;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;
import ua.vtc.rozklad.contracts.Parity;

/**
 * Week numbering and parity of the college calendar (DOMAIN §2) as pure functions: week 1 is the
 * Monday-based week that contains the first day of the semester, odd weeks have the semester's
 * first-week parity, even weeks the other one, and a manual override of the week wins.
 */
public final class StudyWeeks {

    private StudyWeeks() {
    }

    public static LocalDate weekStart(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** 1-based number of the week of {@code date}; 0 or less for dates before the semester week 1. */
    public static int weekNumber(LocalDate semesterStart, LocalDate date) {
        long weeks = ChronoUnit.WEEKS.between(weekStart(semesterStart), weekStart(date));
        return Math.toIntExact(weeks) + 1;
    }

    /**
     * @param override parity set manually for the week of {@code date}, or {@code null}
     * @return NUMERATOR or DENOMINATOR, never BOTH
     */
    public static Parity parity(LocalDate semesterStart, Parity firstWeekParity, LocalDate date, Parity override) {
        Objects.requireNonNull(firstWeekParity, "firstWeekParity");
        if (firstWeekParity == Parity.BOTH) {
            throw new IllegalArgumentException("first week parity must be NUMERATOR or DENOMINATOR");
        }
        if (override != null && override != Parity.BOTH) {
            return override;
        }
        boolean oddWeek = Math.floorMod(weekNumber(semesterStart, date), 2) == 1;
        return oddWeek ? firstWeekParity : opposite(firstWeekParity);
    }

    /** Lessons happen Monday to Friday only. */
    public static boolean isWeekday(LocalDate date) {
        return date.getDayOfWeek().getValue() <= DayOfWeek.FRIDAY.getValue();
    }

    private static Parity opposite(Parity parity) {
        return parity == Parity.NUMERATOR ? Parity.DENOMINATOR : Parity.NUMERATOR;
    }
}
