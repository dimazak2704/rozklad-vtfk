package ua.vtc.rozklad.importer.absence;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.AbsenceReasonCategory;
import ua.vtc.rozklad.importer.text.TextNormalizer;

/**
 * Deterministic Ukrainian parser of absence messages from the bot: «сьогодні», «завтра»,
 * «післязавтра», «у понеділок», «4.11», «4 листопада», «з 4 по 6 листопада», «4.11–6.11»; pairs
 * «1-2 пари», «з 1 по 3 пару», «2 і 3 пари», «на першій парі»; reasons «лікарняний», «відпустка»,
 * «відрядження», family/personal circumstances. Confidence drops when the date had to be assumed.
 */
@Component
public class AbsenceTextParser {

    static final double ASSUMED_DATE_PENALTY = 0.45;

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("січня", 1), Map.entry("лютого", 2), Map.entry("березня", 3), Map.entry("квітня", 4),
            Map.entry("травня", 5), Map.entry("червня", 6), Map.entry("липня", 7), Map.entry("серпня", 8),
            Map.entry("вересня", 9), Map.entry("жовтня", 10), Map.entry("листопада", 11), Map.entry("грудня", 12));
    private static final String MONTH = "(січня|лютого|березня|квітня|травня|червня|липня|серпня|вересня|жовтня|листопада|грудня)";
    private static final String NUMERIC = "(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?";

    private static final Pattern RANGE_WITH_MONTH =
            Pattern.compile("(?:з\\s*)?(\\d{1,2})(?:-?го)?\\s*(?:-|по|до)\\s*(\\d{1,2})(?:-?го)?\\s+" + MONTH);
    private static final Pattern NUMERIC_RANGE = Pattern.compile("(?:з\\s*)?" + NUMERIC + "\\s*(?:-|по|до)\\s*" + NUMERIC);
    private static final Pattern DAY_WITH_MONTH = Pattern.compile("(\\d{1,2})(?:-?го)?\\s+" + MONTH);
    private static final Pattern NUMERIC_DATE = Pattern.compile("(?<![\\d.])" + NUMERIC + "(?![\\d.]*\\d)");
    private static final Pattern WEEKDAY = Pattern.compile(
            "(?:^|\\s)(?:в|у|во)?\\s*(понеділок|вівторок|середу|середа|четвер|п'ятницю|п'ятниця)");

    private static final Pattern PAIR_RANGE = Pattern.compile("(?:з\\s*)?([1-8])\\s*(?:-|по|до)\\s*([1-8])\\s*(?:-?(?:у|а|ої|ій))?\\s*пар");
    private static final Pattern PAIR_LIST = Pattern.compile("((?:[1-8]\\s*(?:-?(?:у|а|ої|ій))?\\s*(?:,|і|та|й)\\s*)+[1-8])\\s*(?:-?(?:у|а|ої|ій))?\\s*пар");
    private static final Pattern PAIR_SINGLE = Pattern.compile("(?<![\\d.])([1-8])\\s*(?:-?(?:у|а|ої|ій|й))?\\s*пар");
    private static final Pattern PAIR_ORDINALS = Pattern.compile(
            "((?:(?:перш|друг|трет|четверт|п'ят|шост|сьом|восьм)[а-яіїєґ']*\\s*(?:,|і|та|й)?\\s*)+)пар");
    private static final Map<String, Integer> ORDINALS = Map.of(
            "перш", 1, "друг", 2, "трет", 3, "четверт", 4, "п'ят", 5, "шост", 6, "сьом", 7, "восьм", 8);
    private static final Pattern WHOLE_DAY = Pattern.compile("(?:весь|цілий|увесь)\\s+день|всі\\s+пари|усі\\s+пари");
    private static final Pattern UNCERTAIN = Pattern.compile("можливо|мабуть|не знаю|ймовірно|може бути");

    public ParsedAbsence parse(String text, LocalDate today) {
        String key = TextNormalizer.key(text).replace('–', '-').replace('—', '-');
        double confidence = 1.0;
        List<String> questions = new ArrayList<>();

        LocalDate[] range = dates(key, today);
        boolean assumed = range == null;
        if (assumed) {
            range = new LocalDate[] {today, today};
            confidence -= ASSUMED_DATE_PENALTY;
            questions.add("на яку дату");
        } else if (range[1].isBefore(range[0])) {
            range = new LocalDate[] {range[1], range[0]};
            confidence -= 0.2;
        }
        if (range[0].isBefore(today.minusDays(30)) || range[1].isAfter(today.plusDays(200))) {
            confidence -= 0.3;
            questions.add("перевірте дати");
        }
        List<Integer> pairs = WHOLE_DAY.matcher(key).find() ? null : pairs(key);
        AbsenceReasonCategory reason = reason(key);
        if (reason == AbsenceReasonCategory.OTHER) {
            confidence -= 0.1;
        }
        if (UNCERTAIN.matcher(key).find()) {
            confidence -= 0.2;
        }
        String clarification = questions.isEmpty() ? null
                : "Уточніть, будь ласка, " + String.join(" і ", questions) + " ви будете відсутні.";
        if (questions.contains("перевірте дати")) {
            clarification = "Перевірте, будь ласка, дати відсутності.";
        }
        return new ParsedAbsence(range[0], range[1], pairs, reason, confidence, clarification, ParsedAbsence.Source.RULES);
    }

    // ───────────────────────── дати ─────────────────────────

    private static LocalDate[] dates(String key, LocalDate today) {
        Matcher matcher = RANGE_WITH_MONTH.matcher(key);
        if (matcher.find()) {
            int month = MONTHS.get(matcher.group(3));
            LocalDate from = withYear(today, month, Integer.parseInt(matcher.group(1)), null);
            LocalDate to = withYear(today, month, Integer.parseInt(matcher.group(2)), null);
            if (from != null && to != null) {
                return new LocalDate[] {from, to};
            }
        }
        matcher = NUMERIC_RANGE.matcher(key);
        if (matcher.find()) {
            LocalDate from = withYear(today, Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(1)),
                    matcher.group(3));
            LocalDate to = withYear(today, Integer.parseInt(matcher.group(5)), Integer.parseInt(matcher.group(4)),
                    matcher.group(6));
            if (from != null && to != null) {
                return new LocalDate[] {from, to};
            }
        }
        List<LocalDate> found = new ArrayList<>();
        matcher = DAY_WITH_MONTH.matcher(key);
        while (matcher.find()) {
            add(found, withYear(today, MONTHS.get(matcher.group(2)), Integer.parseInt(matcher.group(1)), null));
        }
        matcher = NUMERIC_DATE.matcher(key);
        while (matcher.find()) {
            add(found, withYear(today, Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(1)), matcher.group(3)));
        }
        if (key.contains("післязавтра")) {
            add(found, today.plusDays(2));
        }
        if (key.replace("післязавтра", "").contains("завтра")) {
            add(found, today.plusDays(1));
        }
        if (key.contains("сьогодні")) {
            add(found, today);
        }
        matcher = WEEKDAY.matcher(key);
        while (matcher.find()) {
            add(found, today.with(TemporalAdjusters.nextOrSame(weekday(matcher.group(1)))));
        }
        if (found.isEmpty()) {
            return null;
        }
        found.sort(null);
        return new LocalDate[] {found.getFirst(), found.getLast()};
    }

    private static void add(List<LocalDate> dates, LocalDate date) {
        if (date != null && !dates.contains(date)) {
            dates.add(date);
        }
    }

    /** A date without a year that passed more than 14 days ago belongs to the next year. */
    private static LocalDate withYear(LocalDate today, int month, int day, String yearText) {
        try {
            if (yearText != null) {
                int year = Integer.parseInt(yearText);
                return LocalDate.of(year < 100 ? 2000 + year : year, month, day);
            }
            LocalDate candidate = LocalDate.of(today.getYear(), month, day);
            return candidate.isBefore(today.minusDays(14)) ? candidate.plusYears(1) : candidate;
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static DayOfWeek weekday(String word) {
        return switch (word) {
            case "понеділок" -> DayOfWeek.MONDAY;
            case "вівторок" -> DayOfWeek.TUESDAY;
            case "середу", "середа" -> DayOfWeek.WEDNESDAY;
            case "четвер" -> DayOfWeek.THURSDAY;
            default -> DayOfWeek.FRIDAY;
        };
    }

    // ───────────────────────── пари ─────────────────────────

    private static List<Integer> pairs(String key) {
        TreeSet<Integer> pairs = new TreeSet<>();
        Matcher range = PAIR_RANGE.matcher(key);
        while (range.find()) {
            int from = Integer.parseInt(range.group(1));
            int to = Integer.parseInt(range.group(2));
            for (int pair = Math.min(from, to); pair <= Math.max(from, to); pair++) {
                pairs.add(pair);
            }
        }
        if (pairs.isEmpty()) {
            Matcher list = PAIR_LIST.matcher(key);
            while (list.find()) {
                Matcher digit = Pattern.compile("[1-8]").matcher(list.group(1));
                while (digit.find()) {
                    pairs.add(Integer.parseInt(digit.group()));
                }
            }
        }
        if (pairs.isEmpty()) {
            Matcher single = PAIR_SINGLE.matcher(key);
            while (single.find()) {
                pairs.add(Integer.parseInt(single.group(1)));
            }
        }
        Matcher ordinals = PAIR_ORDINALS.matcher(key);
        while (ordinals.find()) {
            for (String word : ordinals.group(1).split("[\\s,]+")) {
                if (word.startsWith("п'ятниц")) {
                    continue;
                }
                ORDINALS.forEach((prefix, pair) -> {
                    if (word.startsWith(prefix)) {
                        pairs.add(pair);
                    }
                });
            }
        }
        return pairs.isEmpty() ? null : List.copyOf(pairs);
    }

    // ───────────────────────── причина ─────────────────────────

    private static AbsenceReasonCategory reason(String key) {
        if (containsAny(key, "лікарнян", "хвор", "захвор", "застуд", "температур", "лікар", "болі", "болит")) {
            return AbsenceReasonCategory.SICK_LEAVE;
        }
        if (key.contains("відпустк")) {
            return AbsenceReasonCategory.VACATION;
        }
        if (containsAny(key, "відрядж", "конференц")) {
            return AbsenceReasonCategory.BUSINESS_TRIP;
        }
        if (containsAny(key, "сімейн", "особист", "власний рахунок", "свій рахунок", "весілл", "похорон")) {
            return AbsenceReasonCategory.PERSONAL;
        }
        return AbsenceReasonCategory.OTHER;
    }

    private static boolean containsAny(String text, String... fragments) {
        for (String fragment : fragments) {
            if (text.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
