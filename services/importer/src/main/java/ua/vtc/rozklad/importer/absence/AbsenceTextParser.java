package ua.vtc.rozklad.importer.absence;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.AbsenceReasonCategory;
import ua.vtc.rozklad.importer.text.TextNormalizer;

/**
 * Deterministic Ukrainian parser of absence messages from the bot: «сьогодні», «завтра»,
 * «післязавтра», «у понеділок», «з понеділка по середу», «4.11», «4 листопада», «11 і 12
 * листопада», «1 січня 2027», «з 4 по 6 листопада», «4.11–6.11»; pairs «1-2 пари», «з 1 по 3 пару»,
 * «2 і 3 пари», «на першій парі»; reasons «лікарняний», «відпустка», «відрядження», family/personal
 * circumstances. Confidence drops when the date had to be assumed.
 *
 * <p>The answer is one date range with one set of pairs. A message that this shape cannot carry —
 * separate days with a gap between them, different pairs on different days, a pair number outside
 * 1–8 — gets a clarification and a low confidence, and is {@linkplain Reading#settled() settled}:
 * no other reader (the AI) can answer it in this shape either, so the teacher is asked.
 */
@Component
public class AbsenceTextParser {

    static final double ASSUMED_DATE_PENALTY = 0.45;
    /** Confidence of a message the answer's shape cannot carry; below the bot's minimum. */
    static final double UNREPRESENTABLE = 0.4;
    static final int FIRST_PAIR = 1;
    static final int LAST_PAIR = 8;

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("січня", 1), Map.entry("лютого", 2), Map.entry("березня", 3), Map.entry("квітня", 4),
            Map.entry("травня", 5), Map.entry("червня", 6), Map.entry("липня", 7), Map.entry("серпня", 8),
            Map.entry("вересня", 9), Map.entry("жовтня", 10), Map.entry("листопада", 11), Map.entry("грудня", 12));
    private static final String MONTH = "(січня|лютого|березня|квітня|травня|червня|липня|серпня|вересня|жовтня|листопада|грудня)";
    /** An optional explicit year after a month name: «1 січня 2030», «1 січня 2030 р.», «… 2030 року». */
    private static final String YEAR = "(?:\\s+(\\d{4})(?!\\d)(?:\\s*(?:р\\.?|року))?)?";
    private static final String NUMERIC = "(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{2,4}))?";
    private static final String DAY = "\\d{1,2}(?:-?го)?";
    private static final String AND = "\\s*(?:,|і|та|й)\\s*";
    private static final String LETTER = "[\\p{L}']";

    private static final Pattern RANGE_WITH_MONTH =
            Pattern.compile("(?:з\\s*)?(\\d{1,2})(?:-?го)?\\s*(?:-|по|до)\\s*(\\d{1,2})(?:-?го)?\\s+" + MONTH + YEAR);
    private static final Pattern NUMERIC_RANGE = Pattern.compile("(?:з\\s*)?" + NUMERIC + "\\s*(?:-|по|до)\\s*" + NUMERIC);
    /** «11 листопада», «11 і 12 листопада», «11, 12 та 13 листопада 2026». */
    private static final Pattern DAYS_WITH_MONTH = Pattern.compile(
            "(?<!\\d)((?:" + DAY + AND + ")*)(\\d{1,2})(?:-?го)?\\s+" + MONTH + YEAR);
    private static final Pattern NUMERIC_DATE = Pattern.compile("(?<![\\d.])" + NUMERIC + "(?![\\d.]*\\d)");
    private static final String WEEKDAY_FORMS =
            "(понеділок|понеділка|вівторок|вівторка|середу|середа|середи|четвер|четверга|п'ятницю|п'ятниця|п'ятниці)";
    private static final Pattern WEEKDAY_RANGE = Pattern.compile(
            "(?<!" + LETTER + ")(?:(?:з|від)\\s+)?" + WEEKDAY_FORMS + "\\s*(?:-|по|до)\\s*(?:в|у|во)?\\s*" + WEEKDAY_FORMS
                    + "(?!" + LETTER + ")");
    private static final Pattern WEEKDAY = Pattern.compile("(?<!" + LETTER + ")" + WEEKDAY_FORMS + "(?!" + LETTER + ")");

    private static final String PAIR_SUFFIX = "(?:-?(?:у|а|ої|ій|й|га))?";
    private static final Pattern PAIR_RANGE = Pattern.compile(
            "(?<![\\d.])(?:з\\s*)?(\\d{1,2})\\s*(?:-|по|до)\\s*(\\d{1,2})\\s*" + PAIR_SUFFIX + "\\s*пар");
    private static final Pattern PAIR_LIST = Pattern.compile(
            "(?<![\\d.])((?:\\d{1,2}\\s*" + PAIR_SUFFIX + AND + ")+\\d{1,2})\\s*" + PAIR_SUFFIX + "\\s*пар");
    private static final Pattern PAIR_SINGLE = Pattern.compile("(?<![\\d.])(\\d{1,2})\\s*" + PAIR_SUFFIX + "\\s*пар");
    private static final Pattern PAIR_ORDINALS = Pattern.compile(
            "((?:(?:перш|друг|трет|четверт|п'ят|шост|сьом|восьм)[а-яіїєґ']*\\s*(?:,|і|та|й)?\\s*)+)пар");
    private static final Pattern DIGITS = Pattern.compile("\\d{1,2}");
    private static final Map<String, Integer> ORDINALS = Map.of(
            "перш", 1, "друг", 2, "трет", 3, "четверт", 4, "п'ят", 5, "шост", 6, "сьом", 7, "восьм", 8);
    private static final Pattern WHOLE_DAY = Pattern.compile("(?:весь|цілий|увесь)\\s+день|всі\\s+пари|усі\\s+пари");
    private static final Pattern UNCERTAIN = Pattern.compile("можливо|мабуть|не знаю|ймовірно|може бути");

    /** The parser's answer and whether it is final: {@code settled} — asking the AI cannot improve it. */
    public record Reading(ParsedAbsence parsed, boolean settled) {
    }

    public ParsedAbsence parse(String text, LocalDate today) {
        return read(text, today).parsed();
    }

    public Reading read(String text, LocalDate today) {
        String key = TextNormalizer.key(text).replace('–', '-').replace('—', '-');
        double confidence = 1.0;
        List<String> questions = new ArrayList<>();

        Dates dates = dates(key, today);
        LocalDate from;
        LocalDate to;
        if (dates.days().isEmpty()) {
            from = today;
            to = today;
            confidence -= ASSUMED_DATE_PENALTY;
            questions.add("на яку дату");
        } else {
            from = dates.days().getFirst().from();
            to = dates.days().stream().map(Span::to).max(Comparator.naturalOrder()).orElseThrow();
            if (dates.reversed()) {
                confidence -= 0.2;
            }
        }
        String clarification = null;
        if (from.isBefore(today.minusDays(30)) || to.isAfter(today.plusDays(200))) {
            confidence -= 0.3;
            clarification = "Перевірте, будь ласка, дати відсутності.";
        }
        boolean wholeDay = WHOLE_DAY.matcher(key).find();
        Pairs pairs = wholeDay ? Pairs.NONE : pairs(key);
        AbsenceReasonCategory reason = reason(key);
        if (reason == AbsenceReasonCategory.OTHER) {
            confidence -= 0.1;
        }
        if (UNCERTAIN.matcher(key).find()) {
            confidence -= 0.2;
        }
        if (clarification == null && !questions.isEmpty()) {
            clarification = "Уточніть, будь ласка, " + String.join(" і ", questions) + " ви будете відсутні.";
        }

        // те, що відповідь (один діапазон дат + одні пари) не може передати, вирішує лише викладач
        String unrepresentable = null;
        if (!pairs.invalid().isEmpty()) {
            unrepresentable = "Пари мають номери від " + FIRST_PAIR + " до " + LAST_PAIR + " — уточніть, будь ласка, "
                    + "які пари ви не проведете.";
        } else if (hasGap(dates.days())) {
            unrepresentable = "Між названими днями є інші навчальні дні. Якщо вас не буде весь цей час, напишіть "
                    + "діапазон («з … по …»); якщо лише в ці дні — надішліть кожен день окремим повідомленням.";
        } else if (!from.equals(to) && pairs.differentPerMention()) {
            unrepresentable = "Для різних днів названо різні пари — надішліть, будь ласка, кожен день окремим "
                    + "повідомленням.";
        }
        if (unrepresentable != null) {
            ParsedAbsence parsed = new ParsedAbsence(from, to, pairs.valid(), reason, Math.min(confidence, UNREPRESENTABLE),
                    unrepresentable, ParsedAbsence.Source.RULES);
            return new Reading(parsed, true);
        }
        return new Reading(new ParsedAbsence(from, to, pairs.valid(), reason, confidence, clarification,
                ParsedAbsence.Source.RULES), false);
    }

    // ───────────────────────── дати ─────────────────────────

    /** A run of days (a single day has {@code from == to}). */
    private record Span(LocalDate from, LocalDate to) {
    }

    /** Spans named in the text, sorted by start; {@code reversed} — a range was written end first. */
    private record Dates(List<Span> days, boolean reversed) {
    }

    private static Dates dates(String key, LocalDate today) {
        List<Span> spans = new ArrayList<>();
        boolean reversed = false;
        StringBuilder rest = new StringBuilder(key);

        Matcher matcher = RANGE_WITH_MONTH.matcher(key);
        while (matcher.find()) {
            int month = MONTHS.get(matcher.group(3));
            LocalDate from = withYear(today, month, Integer.parseInt(matcher.group(1)), matcher.group(4));
            LocalDate to = withYear(today, month, Integer.parseInt(matcher.group(2)), matcher.group(4));
            reversed |= addSpan(spans, from, to);
            blank(rest, matcher);
        }
        matcher = NUMERIC_RANGE.matcher(rest);
        while (matcher.find()) {
            LocalDate from = withYear(today, Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(1)),
                    matcher.group(3));
            LocalDate to = withYear(today, Integer.parseInt(matcher.group(5)), Integer.parseInt(matcher.group(4)),
                    matcher.group(6));
            reversed |= addSpan(spans, from, to);
            blank(rest, matcher);
        }
        matcher = DAYS_WITH_MONTH.matcher(rest);
        while (matcher.find()) {
            int month = MONTHS.get(matcher.group(3));
            Matcher day = DIGITS.matcher(matcher.group(1) + " " + matcher.group(2));
            while (day.find()) {
                LocalDate date = withYear(today, month, Integer.parseInt(day.group()), matcher.group(4));
                addSpan(spans, date, date);
            }
            blank(rest, matcher);
        }
        matcher = NUMERIC_DATE.matcher(rest);
        while (matcher.find()) {
            LocalDate date = withYear(today, Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(1)),
                    matcher.group(3));
            addSpan(spans, date, date);
        }
        if (key.contains("післязавтра")) {
            addSpan(spans, today.plusDays(2), today.plusDays(2));
        }
        if (key.replace("післязавтра", "").contains("завтра")) {
            addSpan(spans, today.plusDays(1), today.plusDays(1));
        }
        if (key.contains("сьогодні")) {
            addSpan(spans, today, today);
        }
        // дні тижня — найближчі, кожен наступний після попереднього: «з понеділка по середу», «у п'ятницю й понеділок»
        LocalDate previous = today;
        matcher = WEEKDAY_RANGE.matcher(rest);
        while (matcher.find()) {
            LocalDate from = previous.with(TemporalAdjusters.nextOrSame(weekday(matcher.group(1))));
            LocalDate to = from.with(TemporalAdjusters.nextOrSame(weekday(matcher.group(2))));
            addSpan(spans, from, to);
            previous = to;
            blank(rest, matcher);
        }
        matcher = WEEKDAY.matcher(rest);
        while (matcher.find()) {
            LocalDate date = previous.with(TemporalAdjusters.nextOrSame(weekday(matcher.group(1))));
            addSpan(spans, date, date);
            previous = date;
        }
        spans.sort(Comparator.comparing(Span::from).thenComparing(Span::to));
        return new Dates(List.copyOf(spans), reversed);
    }

    /** Adds the span (swapped when written end first) and tells whether it was reversed. */
    private static boolean addSpan(List<Span> spans, LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            return false;
        }
        boolean reversed = to.isBefore(from);
        Span span = reversed ? new Span(to, from) : new Span(from, to);
        if (!spans.contains(span)) {
            spans.add(span);
        }
        return reversed;
    }

    /** Hides a matched fragment from the later, looser patterns (keeps positions). */
    private static void blank(StringBuilder text, Matcher matcher) {
        for (int i = matcher.start(); i < matcher.end(); i++) {
            text.setCharAt(i, ' ');
        }
    }

    /** True when a study day (Mon–Fri) between the named days is not named itself. */
    private static boolean hasGap(List<Span> spans) {
        if (spans.size() < 2) {
            return false;
        }
        LocalDate coveredTo = spans.getFirst().to();
        for (Span span : spans.subList(1, spans.size())) {
            for (LocalDate day = coveredTo.plusDays(1); day.isBefore(span.from()); day = day.plusDays(1)) {
                if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                    return true;
                }
            }
            if (span.to().isAfter(coveredTo)) {
                coveredTo = span.to();
            }
        }
        return false;
    }

    /**
     * A date without a year that passed more than 14 days ago belongs to the next year; an explicit
     * year is kept as written (two digits = 20xx).
     */
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
        if (word.startsWith("понеділ")) {
            return DayOfWeek.MONDAY;
        }
        if (word.startsWith("вівтор")) {
            return DayOfWeek.TUESDAY;
        }
        if (word.startsWith("серед")) {
            return DayOfWeek.WEDNESDAY;
        }
        if (word.startsWith("четвер")) {
            return DayOfWeek.THURSDAY;
        }
        return DayOfWeek.FRIDAY;
    }

    // ───────────────────────── пари ─────────────────────────

    /**
     * Pairs named in the text: {@code valid} (sorted, {@code null} = none named), numbers outside
     * 1–8 and whether separate mentions name different pairs («3 пара …, … 1 пара»).
     */
    private record Pairs(List<Integer> valid, Set<Integer> invalid, boolean differentPerMention) {
        static final Pairs NONE = new Pairs(null, Set.of(), false);
    }

    private static Pairs pairs(String key) {
        List<Set<Integer>> mentions = new ArrayList<>();
        StringBuilder rest = new StringBuilder(key);
        Matcher range = PAIR_RANGE.matcher(key);
        while (range.find()) {
            int from = Integer.parseInt(range.group(1));
            int to = Integer.parseInt(range.group(2));
            Set<Integer> mention = new TreeSet<>();
            for (int pair = Math.min(from, to); pair <= Math.max(from, to); pair++) {
                mention.add(pair);
            }
            mentions.add(mention);
            blank(rest, range);
        }
        Matcher list = PAIR_LIST.matcher(rest);
        while (list.find()) {
            Set<Integer> mention = new TreeSet<>();
            Matcher digit = DIGITS.matcher(list.group(1));
            while (digit.find()) {
                mention.add(Integer.parseInt(digit.group()));
            }
            mentions.add(mention);
            blank(rest, list);
        }
        Matcher single = PAIR_SINGLE.matcher(rest);
        while (single.find()) {
            mentions.add(Set.of(Integer.parseInt(single.group(1))));
        }
        Matcher ordinals = PAIR_ORDINALS.matcher(key);
        while (ordinals.find()) {
            Set<Integer> mention = new TreeSet<>();
            for (String word : ordinals.group(1).split("[\\s,]+")) {
                if (word.startsWith("п'ятниц")) {
                    continue;
                }
                ORDINALS.forEach((prefix, pair) -> {
                    if (word.startsWith(prefix)) {
                        mention.add(pair);
                    }
                });
            }
            if (!mention.isEmpty()) {
                mentions.add(mention);
            }
        }
        TreeSet<Integer> valid = new TreeSet<>();
        TreeSet<Integer> invalid = new TreeSet<>();
        for (Set<Integer> mention : mentions) {
            for (int pair : mention) {
                (pair >= FIRST_PAIR && pair <= LAST_PAIR ? valid : invalid).add(pair);
            }
        }
        boolean different = mentions.stream().distinct().count() > 1;
        return new Pairs(valid.isEmpty() ? null : List.copyOf(valid), invalid, different);
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
