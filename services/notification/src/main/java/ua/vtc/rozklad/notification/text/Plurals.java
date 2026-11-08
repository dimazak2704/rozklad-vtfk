package ua.vtc.rozklad.notification.text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ukrainian plural forms: 1 пара, 2 пари, 5 пар, 11 пар, 21 пара. */
public final class Plurals {

    private static final Pattern SHIFTS = Pattern.compile("(?<!\\d)(\\d{1,9})(\\s+)(зміна|зміни|змін)(?!\\p{L})");

    private Plurals() {
    }

    public static String of(long count, String one, String few, String many) {
        long lastTwo = Math.abs(count) % 100;
        long last = Math.abs(count) % 10;
        if (lastTwo >= 11 && lastTwo <= 14) {
            return many;
        }
        if (last == 1) {
            return one;
        }
        return last >= 2 && last <= 4 ? few : many;
    }

    /** {@code 2 змін} → {@code 2 зміни}: a count of shifts inside a label typed by hand (bell profile names). */
    public static String agreeShifts(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = SHIFTS.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            long count = Long.parseLong(matcher.group(1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    matcher.group(1) + matcher.group(2) + of(count, "зміна", "зміни", "змін")));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
