package ua.vtc.rozklad.core.timetable.internal.support;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;

/** Ukrainian wording shared by conflict messages, schedules and calendar exports. */
public final class UkrainianText {

    private static final String[] WEEKDAYS = {"понеділок", "вівторок", "середа", "четвер", "пʼятниця", "субота",
            "неділя"};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private UkrainianText() {
    }

    /** Weekday 1..7 in lower case: «понеділок». */
    public static String weekday(int weekday) {
        if (weekday < 1 || weekday > 7) {
            throw new IllegalArgumentException("weekday must be 1..7");
        }
        return WEEKDAYS[weekday - 1];
    }

    /** Day label of a schedule in the college style: «ПОНЕДІЛОК», «ПʼЯТНИЦЯ». */
    public static String weekdayLabel(int weekday) {
        return weekday(weekday).toUpperCase(Locale.ROOT);
    }

    /** «19.10.2026». */
    public static String date(LocalDate date) {
        return DATE.format(date);
    }

    public static String parity(Parity parity) {
        return switch (parity) {
            case BOTH -> "щотижня";
            case NUMERATOR -> "чисельник";
            case DENOMINATOR -> "знаменник";
        };
    }

    public static String roomType(RoomType type) {
        return switch (type) {
            case CLASSROOM -> "аудиторія";
            case COMPUTER_LAB -> "компʼютерний клас";
            case LAB -> "лабораторія";
            case GYM -> "спортзал";
            case SPORTS_GROUND -> "спортмайданчик";
            case OTHER -> "приміщення";
        };
    }

    /** «1 пара», «3 пари», «5 пар». */
    public static String pairs(int count) {
        return count + " " + plural(count, "пара", "пари", "пар");
    }

    /** «3 рази», «5 разів». */
    public static String times(int count) {
        return count + " " + plural(count, "раз", "рази", "разів");
    }

    static String plural(int count, String one, String few, String many) {
        int lastTwo = Math.abs(count) % 100;
        int last = lastTwo % 10;
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
}
