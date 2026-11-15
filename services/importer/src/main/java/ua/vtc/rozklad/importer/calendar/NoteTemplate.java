package ua.vtc.rozklad.importer.calendar;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import ua.vtc.rozklad.importer.text.TextNormalizer;

/**
 * The order note of a period rendered from its kind's template (shared contract §1.2, the same language as
 * the web form's «Скласти»). Placeholders: {@code {хто}} audience, {@code {назва}} title, {@code {назва_мала}}
 * title with a lower-case first letter («занять немає» when empty), {@code {вид}} kind name, {@code {від}} and
 * {@code {до}} dates {@code dd.MM.yy}, {@code {ауд}} room, {@code {режим}} study mode. {@code [...]} is optional:
 * dropped when a placeholder inside renders empty. An unknown {@code {x}} stays as written; the result is trimmed.
 */
public final class NoteTemplate {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yy");

    private NoteTemplate() {
    }

    /** What the placeholders stand for; {@code studyMode} ONLINE, MIXED, OFFLINE or {@code null}. */
    public record Values(String audience, String title, String kindName, LocalDate from, LocalDate to, String room,
                         String studyMode) {
    }

    /** {@code {хто}}: «Усі групи», «3 курс» / «1, 2 курси», «4КІ1» / «Групи 4КІ1, 4КІ2» (codes in the given order). */
    public static String audience(String scope, List<String> groupCodes, List<Integer> courses) {
        if ("ALL".equals(scope)) {
            return "Усі групи";
        }
        if ("COURSES".equals(scope)) {
            List<Integer> sorted = courses == null ? List.of() : courses.stream().sorted().toList();
            String list = sorted.stream().map(String::valueOf).collect(Collectors.joining(", "));
            return sorted.size() == 1 ? list + " курс" : list + " курси";
        }
        List<String> codes = groupCodes == null ? List.of() : groupCodes;
        return codes.size() == 1 ? codes.getFirst() : "Групи " + String.join(", ", codes);
    }

    /** The note, or {@code null} when the kind has no template. */
    public static String render(String template, Values values) {
        if (template == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            int close = c == '[' ? template.indexOf(']', i + 1) : -1;
            if (close > 0) {
                Rendered optional = renderPlain(template.substring(i + 1, close), values);
                if (!optional.anyEmpty()) {
                    out.append(optional.text());
                }
                i = close + 1;
            } else {
                int next = nextSegment(template, i + 1);
                out.append(renderPlain(template.substring(i, next), values).text());
                i = next;
            }
        }
        return out.toString().strip();
    }

    private record Rendered(String text, boolean anyEmpty) {
    }

    /** Index of the next «[» that opens an optional part (or the end). */
    private static int nextSegment(String template, int from) {
        int open = template.indexOf('[', from);
        while (open >= 0 && template.indexOf(']', open + 1) < 0) {
            open = -1;
        }
        return open < 0 ? template.length() : open;
    }

    private static Rendered renderPlain(String text, Values values) {
        StringBuilder out = new StringBuilder();
        boolean anyEmpty = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            int close = c == '{' ? text.indexOf('}', i + 1) : -1;
            if (close < 0) {
                out.append(c);
                i++;
                continue;
            }
            String name = text.substring(i + 1, close);
            String value = value(name, values);
            if (value == null) {
                out.append(text, i, close + 1);
            } else {
                anyEmpty |= value.isEmpty();
                out.append(value);
            }
            i = close + 1;
        }
        return new Rendered(out.toString(), anyEmpty);
    }

    /** Value of a known placeholder (possibly empty), {@code null} for an unknown one. */
    private static String value(String name, Values values) {
        return switch (name) {
            case "хто" -> orEmpty(values.audience());
            case "назва" -> orEmpty(values.title());
            case "назва_мала" -> lowerFirst(values.title());
            case "вид" -> orEmpty(values.kindName());
            case "від" -> values.from() == null ? "" : DATE.format(values.from());
            case "до" -> values.to() == null ? "" : DATE.format(values.to());
            case "ауд" -> orEmpty(values.room());
            case "режим" -> studyMode(values.studyMode());
            default -> null;
        };
    }

    private static String lowerFirst(String title) {
        String text = orEmpty(title).strip();
        if (text.isEmpty()) {
            return "занять немає";
        }
        int first = text.codePointAt(0);
        int length = Character.charCount(first);
        return text.substring(0, length).toLowerCase(TextNormalizer.UK) + text.substring(length);
    }

    private static String studyMode(String mode) {
        if (mode == null) {
            return "";
        }
        return switch (mode) {
            case "ONLINE" -> "навчаються – дистанційно";
            case "MIXED" -> "навчаються – у змішаному режимі";
            case "OFFLINE" -> "навчаються очно";
            default -> "";
        };
    }

    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }
}
