package ua.vtc.rozklad.core.timetable.internal.importing;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Normalization of DOMAIN.md §10 used to match imported names against the catalog: NFC, lower case,
 * unified apostrophes, Latin look-alikes → Cyrillic in mostly Cyrillic words, collapsed spaces;
 * the matching key additionally drops dots and spaces («Укр.мова.» = «укр мова» = «укрмова»).
 */
final class NameKeys {

    private static final Pattern APOSTROPHES = Pattern.compile("[ʼ'`’‘]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Map<Character, Character> LATIN_LOOK_ALIKES = Map.ofEntries(
            Map.entry('a', 'а'), Map.entry('c', 'с'), Map.entry('e', 'е'), Map.entry('i', 'і'), Map.entry('o', 'о'),
            Map.entry('p', 'р'), Map.entry('x', 'х'), Map.entry('y', 'у'), Map.entry('k', 'к'), Map.entry('m', 'м'),
            Map.entry('t', 'т'), Map.entry('h', 'н'), Map.entry('b', 'в'));

    private NameKeys() {
    }

    /** «  Укр.Мова. » → «укр.мова.» (Cyrillic, lower case, single spaces). */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = Normalizer.normalize(raw, Normalizer.Form.NFC);
        text = APOSTROPHES.matcher(text).replaceAll("'");
        text = SPACES.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
        boolean cyrillicName = mostlyCyrillic(text);
        StringBuilder result = new StringBuilder();
        for (String token : text.split(" ")) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            // ініціали «M.Д.» у кириличному імені теж перетворюємо: у короткому токені більшості немає
            boolean convert = mostlyCyrillic(token) || (cyrillicName && letters(token) <= 3);
            result.append(convert ? toCyrillic(token) : token);
        }
        return result.toString();
    }

    /** Matching key: {@link #normalize} without dots and spaces («Кренцін М. Д.» → «кренцінмд»). */
    static String key(String raw) {
        return normalize(raw).replace(".", "").replace(" ", "");
    }

    /**
     * Canonical group code (DOMAIN.md §10): «1ТК 1» → «1ТК1», «4КИ1» → «4КІ1», «ІКІ1» (Cyrillic І
     * typed instead of 1) → «1КІ1».
     */
    static String groupKey(String raw) {
        if (raw == null) {
            return "";
        }
        String text = SPACES.matcher(Normalizer.normalize(raw, Normalizer.Form.NFC)).replaceAll("")
                .toLowerCase(Locale.ROOT);
        StringBuilder code = new StringBuilder(toCyrillic(text).toUpperCase(Locale.ROOT).replace('И', 'І'));
        if (code.length() > 1 && code.charAt(0) == 'І' && Character.isLetter(code.charAt(1))) {
            code.setCharAt(0, '1');
        }
        return code.toString();
    }

    private static boolean mostlyCyrillic(String token) {
        int cyrillic = 0;
        int latin = 0;
        for (int i = 0; i < token.length(); i++) {
            char character = token.charAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(character);
            if (script == Character.UnicodeScript.CYRILLIC) {
                cyrillic++;
            } else if (script == Character.UnicodeScript.LATIN) {
                latin++;
            }
        }
        return cyrillic > latin;
    }

    private static int letters(String token) {
        return (int) token.chars().filter(Character::isLetter).count();
    }

    private static String toCyrillic(String token) {
        StringBuilder converted = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char character = token.charAt(i);
            converted.append(LATIN_LOOK_ALIKES.getOrDefault(character, character));
        }
        return converted.toString();
    }
}
