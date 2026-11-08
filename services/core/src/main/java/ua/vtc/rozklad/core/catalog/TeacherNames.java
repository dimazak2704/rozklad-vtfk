package ua.vtc.rozklad.core.catalog;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Teacher name forms used by schedules and orders: short «Кренцін М.Д.», display «Кренцін», full
 * «Кренцін Микола Дмитрович». Real short forms from the Excel include «Крисак Л.», «Овчар Ін.»
 * (two-letter initial) and «ХарченкоГ.» (missing space).
 */
public final class TeacherNames {

    /** Surname followed by one or two initials of one or two letters. */
    private static final Pattern SHORT_FORM = Pattern.compile(
            "^(.+?)\\s*(?:[А-ЯІЇЄҐA-Z][а-яіїєґa-z]?\\.\\s*){1,2}$");

    private TeacherNames() {
    }

    /** «Кренцін М.Д.»; without a first name the surname alone. */
    public static String shortName(String lastName, String firstName, String middleName) {
        StringBuilder out = new StringBuilder(CatalogText.clean(lastName));
        if (hasText(firstName)) {
            out.append(' ').append(initial(firstName)).append('.');
            if (hasText(middleName)) {
                out.append(initial(middleName)).append('.');
            }
        }
        return out.toString();
    }

    /** «Кренцін Микола Дмитрович» when the first name is known, otherwise {@code fallback}. */
    public static String fullName(String lastName, String firstName, String middleName, String fallback) {
        if (!hasText(firstName)) {
            return fallback;
        }
        StringBuilder out = new StringBuilder(CatalogText.clean(lastName)).append(' ').append(CatalogText.clean(firstName));
        if (hasText(middleName)) {
            out.append(' ').append(CatalogText.clean(middleName));
        }
        return out.toString();
    }

    /** Surname of a short form: «Кренцін М.Д.» → «Кренцін», «ХарченкоГ.» → «Харченко». */
    public static String lastNameOf(String shortName) {
        String cleaned = CatalogText.clean(shortName);
        Matcher matcher = SHORT_FORM.matcher(cleaned);
        if (matcher.matches()) {
            return matcher.group(1).strip();
        }
        int space = cleaned.indexOf(' ');
        return space > 0 ? cleaned.substring(0, space) : cleaned;
    }

    private static String initial(String name) {
        return CatalogText.clean(name).substring(0, 1).toUpperCase(Locale.forLanguageTag("uk"));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
