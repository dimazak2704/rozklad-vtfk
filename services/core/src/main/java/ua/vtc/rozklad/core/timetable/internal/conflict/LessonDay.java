package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Day of a lesson for the conflict engine: a weekday of the weekly template or a concrete date.
 * Template days are checked separately for numerator and denominator weeks, a date is one week.
 */
public record LessonDay(int weekday, LocalDate date) implements Comparable<LessonDay> {

    private static final List<WeekType> TEMPLATE_WEEKS = List.of(WeekType.NUMERATOR, WeekType.DENOMINATOR);
    private static final List<WeekType> DATED_WEEK = List.of(WeekType.SINGLE);

    public LessonDay {
        if (weekday < 1 || weekday > 7) {
            throw new IllegalArgumentException("weekday must be 1..7");
        }
        if (date != null && date.getDayOfWeek().getValue() != weekday) {
            throw new IllegalArgumentException("weekday does not match the date " + date);
        }
    }

    public static LessonDay ofWeekday(int weekday) {
        return new LessonDay(weekday, null);
    }

    public static LessonDay ofDate(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return new LessonDay(date.getDayOfWeek().getValue(), date);
    }

    public boolean isDated() {
        return date != null;
    }

    /** Week types in which lessons of this day are compared. */
    public List<WeekType> weeks() {
        return isDated() ? DATED_WEEK : TEMPLATE_WEEKS;
    }

    @Override
    public int compareTo(LessonDay other) {
        if (isDated() && other.isDated()) {
            return date.compareTo(other.date);
        }
        if (!isDated() && !other.isDated()) {
            return Integer.compare(weekday, other.weekday);
        }
        return isDated() ? 1 : -1;
    }
}
