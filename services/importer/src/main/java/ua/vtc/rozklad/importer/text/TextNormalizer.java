package ua.vtc.rozklad.importer.text;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Text normalization of DOMAIN §10: NFC, unified apostrophes, Latin look-alikes → Cyrillic inside
 * mostly Cyrillic tokens, collapsed whitespace. {@link #key} is the comparison form,
 * {@link #abbreviationKey} additionally drops dots, spaces and hyphens («Укр.мова.» = «Укр.мова»).
 */
public final class TextNormalizer {

    public static final Locale UK = Locale.forLanguageTag("uk");

    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0\\u2007\\u202F]+");
    private static final Pattern APOSTROPHES = Pattern.compile("[ʼ'`’‘]");
    private static final Pattern ABBREVIATION_NOISE = Pattern.compile("[.\\s\\-–—_,]+");

    // латинські двійники кириличних літер (у нижньому регістрі)
    private static final String LATIN = "acei" + "opxykmthb";
    private static final String CYRILLIC = "асеі" + "орхукмтнв";

    private TextNormalizer() {
    }

    /** NFC, NBSP → space, collapsed whitespace, trimmed; {@code null} → empty string. */
    public static String clean(String text) {
        if (text == null) {
            return "";
        }
        String nfc = Normalizer.normalize(text, Normalizer.Form.NFC);
        return WHITESPACE.matcher(nfc).replaceAll(" ").strip();
    }

    public static boolean isBlank(String text) {
        return clean(text).isEmpty();
    }

    /** All apostrophe variants (ʼ ' ` ’ ‘) become ASCII {@code '}. */
    public static String unifyApostrophes(String text) {
        return APOSTROPHES.matcher(text).replaceAll("'");
    }

    /** Comparison key: cleaned, lower case, unified apostrophes, Latin look-alikes fixed. */
    public static String key(String text) {
        String lower = unifyApostrophes(clean(text)).toLowerCase(UK);
        return fixLatinLookalikes(lower);
    }

    /** Key for abbreviations: {@link #key} without dots, spaces, hyphens and commas. */
    public static String abbreviationKey(String text) {
        return ABBREVIATION_NOISE.matcher(key(text)).replaceAll("");
    }

    /**
     * Replaces Latin look-alike letters by Cyrillic ones inside tokens that are mostly Cyrillic
     * (a Latin word such as «Project» stays untouched). Expects lower-case input.
     */
    static String fixLatinLookalikes(String lower) {
        StringBuilder result = new StringBuilder(lower.length());
        int i = 0;
        while (i < lower.length()) {
            if (!Character.isLetter(lower.charAt(i))) {
                result.append(lower.charAt(i++));
                continue;
            }
            int start = i;
            while (i < lower.length() && (Character.isLetter(lower.charAt(i)) || lower.charAt(i) == '\'')) {
                i++;
            }
            result.append(fixToken(lower.substring(start, i)));
        }
        return result.toString();
    }

    private static String fixToken(String token) {
        int cyrillic = 0;
        int latin = 0;
        for (int k = 0; k < token.length(); k++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(token.charAt(k));
            if (script == Character.UnicodeScript.CYRILLIC) {
                cyrillic++;
            } else if (script == Character.UnicodeScript.LATIN) {
                latin++;
            }
        }
        if (latin == 0 || cyrillic < latin) {
            return token;
        }
        StringBuilder fixed = new StringBuilder(token.length());
        for (int k = 0; k < token.length(); k++) {
            char c = token.charAt(k);
            int index = LATIN.indexOf(c);
            fixed.append(index >= 0 ? CYRILLIC.charAt(index) : c);
        }
        return fixed.toString();
    }
}
