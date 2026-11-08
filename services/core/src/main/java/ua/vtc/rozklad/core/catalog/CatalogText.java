package ua.vtc.rozklad.core.catalog;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Text normalization of DOMAIN §10, the same rules as {@code web/src/lib/search.ts}: NFC, lower
 * case, apostrophes and «ё» unified, Latin look-alikes replaced by Cyrillic letters in tokens that
 * are mostly Cyrillic, whitespace collapsed. {@link #key(String)} additionally drops dots, spaces
 * and other separators: it is the natural key of aliases, teacher short names and room names
 * («Укр.мова.» and «Укр. мова» → {@code укрмова}).
 */
public final class CatalogText {

    public static final Locale UKRAINIAN = Locale.forLanguageTag("uk");

    /** Latin letters that look like Cyrillic ones (DOMAIN §10). */
    static final Map<Character, Character> LATIN_LOOKALIKES = Map.ofEntries(
            Map.entry('a', 'а'), Map.entry('c', 'с'), Map.entry('e', 'е'), Map.entry('i', 'і'),
            Map.entry('o', 'о'), Map.entry('p', 'р'), Map.entry('x', 'х'), Map.entry('y', 'у'),
            Map.entry('k', 'к'), Map.entry('m', 'м'), Map.entry('t', 'т'), Map.entry('h', 'н'),
            Map.entry('b', 'в'));

    /** Physical keys of the English layout → the Ukrainian layout («rhtywsy» → «кренцін»). */
    private static final Map<Character, Character> EN_TO_UK_LAYOUT = Map.ofEntries(
            Map.entry('q', 'й'), Map.entry('w', 'ц'), Map.entry('e', 'у'), Map.entry('r', 'к'),
            Map.entry('t', 'е'), Map.entry('y', 'н'), Map.entry('u', 'г'), Map.entry('i', 'ш'),
            Map.entry('o', 'щ'), Map.entry('p', 'з'), Map.entry('[', 'х'), Map.entry(']', 'ї'),
            Map.entry('a', 'ф'), Map.entry('s', 'і'), Map.entry('d', 'в'), Map.entry('f', 'а'),
            Map.entry('g', 'п'), Map.entry('h', 'р'), Map.entry('j', 'о'), Map.entry('k', 'л'),
            Map.entry('l', 'д'), Map.entry(';', 'ж'), Map.entry('\'', 'є'), Map.entry('z', 'я'),
            Map.entry('x', 'ч'), Map.entry('c', 'с'), Map.entry('v', 'м'), Map.entry('b', 'и'),
            Map.entry('n', 'т'), Map.entry('m', 'ь'), Map.entry(',', 'б'), Map.entry('.', 'ю'),
            Map.entry('`', 'ʼ'));

    private static final Pattern APOSTROPHES = Pattern.compile("['`ʼ’‘ʹ′´]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern KEY_SEPARATORS = Pattern.compile("[\\s.\\-_,()/]+");

    private CatalogText() {
    }

    /** Normalized text for comparisons: {@code "Кренцiн  М.Д."} (Latin i) → {@code "кренцін м.д."}. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String base = APOSTROPHES.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC).toLowerCase(UKRAINIAN))
                .replaceAll("")
                .replace('ё', 'е');
        return Arrays.stream(WHITESPACE.split(base.strip()))
                .filter(token -> !token.isEmpty())
                .map(token -> isMostlyCyrillic(token) ? replaceLookalikes(token) : token)
                .collect(Collectors.joining(" "));
    }

    /** Natural key without separators: {@code "Кренцін М. Д."} → {@code "кренцінмд"}. */
    public static String key(String raw) {
        return KEY_SEPARATORS.matcher(normalize(raw)).replaceAll("");
    }

    /** Collapses inner whitespace and trims; keeps the original letters for display. */
    public static String clean(String raw) {
        return raw == null ? null : WHITESPACE.matcher(raw.strip()).replaceAll(" ");
    }

    /** Text typed with the English layout while meaning Ukrainian. */
    public static String fromEnglishLayout(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (char ch : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            out.append(EN_TO_UK_LAYOUT.getOrDefault(ch, ch));
        }
        return out.toString();
    }

    static String replaceLookalikes(String token) {
        StringBuilder out = new StringBuilder(token.length());
        for (char ch : token.toCharArray()) {
            out.append(LATIN_LOOKALIKES.getOrDefault(ch, ch));
        }
        return out.toString();
    }

    static boolean isCyrillic(char ch) {
        return Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CYRILLIC;
    }

    public static boolean containsCyrillic(String text) {
        return text.chars().anyMatch(ch -> isCyrillic((char) ch));
    }

    private static boolean isMostlyCyrillic(String token) {
        int cyrillic = 0;
        int latin = 0;
        for (char ch : token.toCharArray()) {
            if (isCyrillic(ch)) {
                cyrillic++;
            } else if (ch >= 'a' && ch <= 'z') {
                latin++;
            }
        }
        return cyrillic > 0 && cyrillic >= latin;
    }
}
