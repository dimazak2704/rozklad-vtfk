package ua.vtc.rozklad.importer.text;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical student group code {@code ^\d[А-ЯІЇЄҐ]{1,3}\d?$} (DOMAIN §10): «4КІ1» = course 4,
 * specialty letters «КІ», number 1. Typos fixed by {@link #parse}: spaces («1ТК 1»), Latin
 * look-alikes («4KI1»), a Cyrillic «І» typed instead of the course digit «1» («ІКІ1»), «И» instead
 * of «І» («4КИ1»).
 */
public record GroupCode(String code, int course, String letters, Integer number) {

    private static final Pattern CANONICAL = Pattern.compile("^(\\d)([А-ЯІЇЄҐ]{1,3})(\\d)?$");
    private static final String LATIN_UPPER = "ABCEHIKMOPTXY";
    private static final String CYRILLIC_UPPER = "АВСЕНІКМОРТХУ";

    /** Parses and fixes a raw code; empty when the text is not a group code even after fixing. */
    public static Optional<GroupCode> parse(String raw) {
        String text = TextNormalizer.clean(raw).replace(" ", "").toUpperCase(TextNormalizer.UK);
        if (text.isEmpty() || text.length() > 8) {
            return Optional.empty();
        }
        StringBuilder fixed = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int latin = LATIN_UPPER.indexOf(c);
            if (latin >= 0) {
                c = CYRILLIC_UPPER.charAt(latin);
            }
            if (c == 'И') {
                c = 'І';
            }
            fixed.append(c);
        }
        // «ІКІ1»: кирилична «І» замість цифри 1 на початку коду
        if (fixed.length() > 1 && (fixed.charAt(0) == 'І' || fixed.charAt(0) == '|')
                && Character.isLetter(fixed.charAt(1))) {
            fixed.setCharAt(0, '1');
        }
        Matcher matcher = CANONICAL.matcher(fixed);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int course = Integer.parseInt(matcher.group(1));
        if (course < 1 || course > 6) {
            return Optional.empty();
        }
        Integer number = matcher.group(3) == null ? null : Integer.parseInt(matcher.group(3));
        return Optional.of(new GroupCode(fixed.toString(), course, matcher.group(2), number));
    }

    /** Whether the cleaned raw text differs from the canonical code (a typo or formatting was fixed). */
    public static boolean wasCorrected(String raw, GroupCode code) {
        return !TextNormalizer.clean(raw).equals(code.code());
    }

    @Override
    public String toString() {
        return code;
    }
}
