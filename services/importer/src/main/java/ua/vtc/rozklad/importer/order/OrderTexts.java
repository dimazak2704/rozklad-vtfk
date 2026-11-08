package ua.vtc.rozklad.importer.order;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.importer.text.TextNormalizer;

/** Header lines and row-type rules of the college order layout (DOMAIN §8), shared by DOCX and AI paths. */
public final class OrderTexts {

    static final Map<String, Integer> MONTHS_GENITIVE = Map.ofEntries(
            Map.entry("січня", 1), Map.entry("лютого", 2), Map.entry("березня", 3), Map.entry("квітня", 4),
            Map.entry("травня", 5), Map.entry("червня", 6), Map.entry("липня", 7), Map.entry("серпня", 8),
            Map.entry("вересня", 9), Map.entry("жовтня", 10), Map.entry("листопада", 11), Map.entry("грудня", 12));

    private static final Pattern NUMBER = Pattern.compile("розпорядження№?(\\d{1,4})");
    private static final Pattern AMENDMENT = Pattern.compile("доповнення(?:до)?розпорядження№?(\\d{1,4})");
    private static final Pattern DATE_WORDS = Pattern.compile("на\\s+(\\d{1,2})\\s+([а-яіїєґ']+)\\s+(\\d{4})");
    private static final Pattern DATE_DIGITS = Pattern.compile("^(\\d{1,2})[.](\\d{1,2})[.](\\d{2,4})$");
    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern DAY_AND_PARITY =
            Pattern.compile("(понеділок|вівторок|середа|четвер|п'ятниця|пятниця)\\s*[-–—]?\\s*(чисельник|знаменник)");
    private static final Pattern PAIR_RANGE = Pattern.compile("^\\d\\s*[-–—]\\s*\\d$");

    private OrderTexts() {
    }

    /** «Р О З П О Р Я Д Ж Е Н Н Я №35» → 35 (letters may be spaced). */
    public static Optional<Integer> number(String line) {
        Matcher matcher = NUMBER.matcher(TextNormalizer.key(line).replace(" ", ""));
        return matcher.find() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
    }

    /** «ДОПОВНЕННЯ до розпорядження №35» → 35. */
    public static Optional<Integer> amendmentOf(String line) {
        Matcher matcher = AMENDMENT.matcher(TextNormalizer.key(line).replace(" ", ""));
        return matcher.find() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
    }

    /** «На 19 жовтня 2026 р.» → 2026-10-19. */
    public static Optional<LocalDate> dateInWords(String line) {
        Matcher matcher = DATE_WORDS.matcher(TextNormalizer.key(line));
        if (!matcher.find() || !MONTHS_GENITIVE.containsKey(matcher.group(2))) {
            return Optional.empty();
        }
        return safeDate(Integer.parseInt(matcher.group(3)), MONTHS_GENITIVE.get(matcher.group(2)),
                Integer.parseInt(matcher.group(1)));
    }

    /** ISO «2026-10-19» or «19.10.2026» / «19.10.26». */
    public static Optional<LocalDate> date(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String clean = TextNormalizer.clean(text);
        if (ISO_DATE.matcher(clean).matches()) {
            try {
                return Optional.of(LocalDate.parse(clean));
            } catch (DateTimeException e) {
                return Optional.empty();
            }
        }
        Matcher digits = DATE_DIGITS.matcher(clean);
        if (digits.matches()) {
            int year = Integer.parseInt(digits.group(3));
            return safeDate(year < 100 ? 2000 + year : year, Integer.parseInt(digits.group(2)),
                    Integer.parseInt(digits.group(1)));
        }
        return dateInWords(clean);
    }

    /** «ПОНЕДІЛОК - ЗНАМЕННИК» → [ПОНЕДІЛОК, ЗНАМЕННИК]. */
    public static Optional<List<String>> weekdayAndParity(String line) {
        Matcher matcher = DAY_AND_PARITY.matcher(TextNormalizer.key(line));
        if (!matcher.find()) {
            return Optional.empty();
        }
        String weekday = matcher.group(1).equals("пятниця") ? "п'ятниця" : matcher.group(1);
        return Optional.of(List.of(weekday.toUpperCase(TextNormalizer.UK), matcher.group(2).toUpperCase(TextNormalizer.UK)));
    }

    /**
     * Row type from the texts of a CHANGES row (DOMAIN §8): «Вичит.» without a replacement is a
     * finished discipline (also over a pair range, «1-3 | … | Вичит.»), a pair range a block,
     * «ДОДАТКОВО» or an empty left side an additional lesson, «За розкладом» as scheduled,
     * «Пари не буде» a cancellation.
     */
    public static OrderRowType rowType(String pairLabel, String fromDiscipline, String fromTeacher, String reason,
                                       String toDiscipline, String toTeacher) {
        String left = TextNormalizer.key(join(fromDiscipline, fromTeacher));
        String right = TextNormalizer.key(join(toDiscipline, toTeacher));
        if (finished(reason, toDiscipline, toTeacher)) {
            return OrderRowType.FINISHED;
        }
        if (PAIR_RANGE.matcher(TextNormalizer.clean(pairLabel)).matches()) {
            return OrderRowType.BLOCK;
        }
        if (left.contains("додатково") || left.isEmpty() && !right.isEmpty()) {
            return OrderRowType.ADDITIONAL;
        }
        if (left.contains("за розкладом")) {
            return OrderRowType.AS_SCHEDULED;
        }
        if (right.contains("пари не буде")) {
            return OrderRowType.CANCELLATION;
        }
        return OrderRowType.REPLACEMENT;
    }

    /** «Вичит.» in the reason and nothing on the right side: the discipline's hours are done. */
    public static boolean finished(String reason, String toDiscipline, String toTeacher) {
        return TextNormalizer.key(join(toDiscipline, toTeacher)).isEmpty()
                && TextNormalizer.key(reason).startsWith("вичит");
    }

    static String join(String a, String b) {
        return (a == null ? "" : a) + " " + (b == null ? "" : b);
    }

    private static Optional<LocalDate> safeDate(int year, int month, int day) {
        try {
            return Optional.of(LocalDate.of(year, month, day));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }
}
