package ua.vtc.rozklad.notification.text;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import ua.vtc.rozklad.contracts.Parity;

/** Ukrainian date and time wording for bot messages and documents. */
public final class UaDates {

    private static final List<String> MONTHS_GENITIVE = List.of("січня", "лютого", "березня", "квітня", "травня",
            "червня", "липня", "серпня", "вересня", "жовтня", "листопада", "грудня");
    private static final List<String> MONTHS_NOMINATIVE = List.of("січень", "лютий", "березень", "квітень",
            "травень", "червень", "липень", "серпень", "вересень", "жовтень", "листопад", "грудень");
    private static final List<String> WEEKDAYS = List.of("понеділок", "вівторок", "середа", "четвер", "п’ятниця",
            "субота", "неділя");
    private static final List<String> WEEKDAYS_SHORT = List.of("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private UaDates() {
    }

    /** {@code 19 жовтня}. */
    public static String dayMonth(LocalDate date) {
        return date.getDayOfMonth() + " " + MONTHS_GENITIVE.get(date.getMonthValue() - 1);
    }

    /** {@code 19 жовтня 2026 р.} (order header). */
    public static String dayMonthYear(LocalDate date) {
        return dayMonth(date) + " " + date.getYear() + " р.";
    }

    /** {@code понеділок}. */
    public static String weekday(LocalDate date) {
        return weekday(date.getDayOfWeek());
    }

    public static String weekday(DayOfWeek day) {
        return WEEKDAYS.get(day.getValue() - 1);
    }

    /** {@code Пн}. */
    public static String weekdayShort(LocalDate date) {
        return WEEKDAYS_SHORT.get(date.getDayOfWeek().getValue() - 1);
    }

    /** {@code понеділок, 19 жовтня}. */
    public static String weekdayDayMonth(LocalDate date) {
        return weekday(date) + ", " + dayMonth(date);
    }

    /** {@code жовтень 2026}. */
    public static String monthYear(int year, int month) {
        return MONTHS_NOMINATIVE.get(month - 1) + " " + year;
    }

    /** {@code 19.10}. */
    public static String shortDate(LocalDate date) {
        return SHORT.format(date);
    }

    /** {@code 19.10.2026}. */
    public static String fullDate(LocalDate date) {
        return FULL.format(date);
    }

    /** {@code 09:40}. */
    public static String time(LocalTime time) {
        return TIME.format(time);
    }

    /** {@code 09:40–10:40}, or empty when a bound is unknown. */
    public static String timeRange(LocalTime start, LocalTime end) {
        if (start == null || end == null) {
            return "";
        }
        return time(start) + "–" + time(end);
    }

    /** {@code 19–21 жовтня}, {@code 30 жовтня – 2 листопада}, or one day. */
    public static String range(LocalDate from, LocalDate to) {
        if (to == null || from.equals(to)) {
            return weekdayDayMonth(from);
        }
        if (from.getYear() == to.getYear() && from.getMonth() == to.getMonth()) {
            return from.getDayOfMonth() + "–" + dayMonth(to);
        }
        return dayMonth(from) + " – " + dayMonth(to);
    }

    /** {@code чисельник} / {@code знаменник}; {@code null} for BOTH or unknown. */
    public static String parity(Parity parity) {
        if (parity == null) {
            return null;
        }
        return switch (parity) {
            case NUMERATOR -> "чисельник";
            case DENOMINATOR -> "знаменник";
            case BOTH -> null;
        };
    }

    public static String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
