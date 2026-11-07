package ua.vtc.rozklad.core.shared.text;

import java.text.Collator;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Locale;
import ua.vtc.rozklad.contracts.Parity;

/**
 * Ukrainian wording used in orders, chips and messages: weekday and month names, parity labels,
 * plural forms and alphabetical ordering. The apostrophe of «П'ЯТНИЦЯ» is the ASCII one, as in
 * the college timetable and orders.
 */
public final class UkrainianText {

    public static final Locale LOCALE = Locale.forLanguageTag("uk");

    private static final String[] WEEKDAYS = {"Понеділок", "Вівторок", "Середа", "Четвер", "П'ятниця", "Субота",
            "Неділя"};
    private static final String[] MONTHS_GENITIVE = {"січня", "лютого", "березня", "квітня", "травня", "червня",
            "липня", "серпня", "вересня", "жовтня", "листопада", "грудня"};

    private UkrainianText() {
    }

    /** «Понеділок». */
    public static String weekday(DayOfWeek day) {
        return WEEKDAYS[day.getValue() - 1];
    }

    /** «ПОНЕДІЛОК», «П'ЯТНИЦЯ» (order header). */
    public static String weekdayUpper(DayOfWeek day) {
        return weekday(day).toUpperCase(LOCALE);
    }

    /** «ЧИСЕЛЬНИК» / «ЗНАМЕННИК» (order header). */
    public static String parityUpper(Parity parity) {
        return switch (parity) {
            case NUMERATOR -> "ЧИСЕЛЬНИК";
            case DENOMINATOR -> "ЗНАМЕННИК";
            case BOTH -> throw new IllegalArgumentException("a week is either NUMERATOR or DENOMINATOR");
        };
    }

    /** «19 жовтня». */
    public static String dayMonth(LocalDate date) {
        return date.getDayOfMonth() + " " + MONTHS_GENITIVE[date.getMonthValue() - 1];
    }

    /** «19 жовтня 2026». */
    public static String dayMonthYear(LocalDate date) {
        return dayMonth(date) + " " + date.getYear();
    }

    /**
     * Plural form for a count: {@code plural(1, "пара", "пари", "пар")} → «пара», 3 → «пари», 5 and
     * 11..14 → «пар».
     */
    public static String plural(long count, String one, String few, String many) {
        long lastTwo = Math.abs(count) % 100;
        long last = lastTwo % 10;
        if (lastTwo >= 11 && lastTwo <= 14) {
            return many;
        }
        if (last == 1) {
            return one;
        }
        if (last >= 2 && last <= 4) {
            return few;
        }
        return many;
    }

    /** Alphabetical order of Ukrainian strings (group codes, surnames). */
    public static Comparator<String> collator() {
        Collator collator = Collator.getInstance(LOCALE);
        return collator::compare;
    }
}
