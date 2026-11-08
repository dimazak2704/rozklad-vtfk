package ua.vtc.rozklad.core.catalog;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical group codes (DOMAIN §10): course digit, 1–3 specialty letters, optional number —
 * {@code ^\d[А-ЯІЇЄҐ]{1,3}\d?$}. Tolerates what people and the Excel really type: «4ki1» → «4КІ1»
 * (Latin look-alikes), «ІКІ1» → «1КІ1» (Cyrillic І instead of the digit), «1ТК 1» → «1ТК1»,
 * «4КИ1» → «4КІ1» (specialty letters of the college never contain «И»; it is the Russian layout).
 * Same rules as {@code canonicalGroupCode} in {@code web/src/lib/search.ts}.
 */
public final class GroupCodes {

    public static final Pattern CANONICAL = Pattern.compile("^(\\d)([А-ЯІЇЄҐ]{1,3})(\\d?)$");

    private static final Pattern LOOSE = Pattern.compile("^([0-9іi|l])([a-zа-яіїєґ]{1,3})(\\d?)$");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s.\\-_]+");
    private static final Pattern CYRILLIC_LETTERS = Pattern.compile("^[а-яіїєґ]+$");

    /** Phonetic transliteration: «2ob» is typed meaning «2ОБ». */
    private static final Map<Character, Character> LATIN_PHONETIC = Map.ofEntries(
            Map.entry('a', 'а'), Map.entry('b', 'б'), Map.entry('c', 'ц'), Map.entry('d', 'д'),
            Map.entry('e', 'е'), Map.entry('f', 'ф'), Map.entry('g', 'г'), Map.entry('h', 'х'),
            Map.entry('i', 'і'), Map.entry('j', 'й'), Map.entry('k', 'к'), Map.entry('l', 'л'),
            Map.entry('m', 'м'), Map.entry('n', 'н'), Map.entry('o', 'о'), Map.entry('p', 'п'),
            Map.entry('q', 'к'), Map.entry('r', 'р'), Map.entry('s', 'с'), Map.entry('t', 'т'),
            Map.entry('u', 'у'), Map.entry('v', 'в'), Map.entry('w', 'в'), Map.entry('x', 'х'),
            Map.entry('y', 'и'), Map.entry('z', 'з'));

    private GroupCodes() {
    }

    /** Canonical form or empty when the text does not look like a group code. */
    public static Optional<String> canonical(String raw) {
        return loose(raw).map(parts -> build(parts.course(), CatalogText.replaceLookalikes(parts.letters()),
                parts.number()));
    }

    /** All plausible codes of a search query: visual look-alikes and phonetic transliteration. */
    public static List<String> variants(String raw) {
        Optional<LooseParts> parts = loose(raw);
        if (parts.isEmpty()) {
            return List.of();
        }
        LooseParts value = parts.get();
        Set<String> out = new LinkedHashSet<>();
        String visual = build(value.course(), CatalogText.replaceLookalikes(value.letters()), value.number());
        if (visual != null) {
            out.add(visual);
        }
        StringBuilder phonetic = new StringBuilder();
        for (char ch : value.letters().toCharArray()) {
            phonetic.append(LATIN_PHONETIC.getOrDefault(ch, ch));
        }
        String phoneticCode = build(value.course(), phonetic.toString(), value.number());
        if (phoneticCode != null) {
            out.add(phoneticCode);
        }
        return List.copyOf(out);
    }

    /**
     * Specialty letters as they appear in group codes: «ki», «К І», «КИ» → «КІ»; empty when the
     * text is not 1–3 letters.
     */
    public static Optional<String> specialtyLetters(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String compact = SEPARATORS.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC).strip()
                .toLowerCase(CatalogText.UKRAINIAN)).replaceAll("");
        if (compact.isEmpty() || compact.length() > 3) {
            return Optional.empty();
        }
        return Optional.ofNullable(build("", CatalogText.replaceLookalikes(compact), ""));
    }

    /** Parts of a canonical code; empty for anything else. */
    public static Optional<GroupCode> parse(String canonicalCode) {
        if (canonicalCode == null) {
            return Optional.empty();
        }
        Matcher matcher = CANONICAL.matcher(canonicalCode);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        Integer number = matcher.group(3).isEmpty() ? null : Integer.valueOf(matcher.group(3));
        return Optional.of(new GroupCode(Integer.parseInt(matcher.group(1)), matcher.group(2), number));
    }

    private static Optional<LooseParts> loose(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String compact = SEPARATORS.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC).strip()
                .toLowerCase(CatalogText.UKRAINIAN)).replaceAll("");
        Matcher matcher = LOOSE.matcher(compact);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String courseRaw = matcher.group(1);
        // на місці курсу кириличну «І», латинську «i», «l» чи «|» набирають замість одиниці
        String course = Character.isDigit(courseRaw.charAt(0)) ? courseRaw : "1";
        return Optional.of(new LooseParts(course, matcher.group(2), matcher.group(3)));
    }

    private static String build(String course, String letters, String number) {
        String fixed = letters.replace('и', 'і');
        if (!CYRILLIC_LETTERS.matcher(fixed).matches()) {
            return null;
        }
        return course + fixed.toUpperCase(CatalogText.UKRAINIAN) + number;
    }

    /** Parsed canonical code «4КІ1» → course 4, letters «КІ», number 1 (absent for «4ВЕ»). */
    public record GroupCode(int course, String letters, Integer number) {

        public String value() {
            return course + letters + (number == null ? "" : number.toString());
        }
    }

    private record LooseParts(String course, String letters, String number) {
    }
}
