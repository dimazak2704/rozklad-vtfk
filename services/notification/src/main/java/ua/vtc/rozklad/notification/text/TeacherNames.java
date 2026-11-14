package ua.vtc.rozklad.notification.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How the bot writes a teacher in schedule replies: surname and initials with spaces, «Кренцін М. Д.»,
 * from the college's short form («Кренцін М.Д.», «Овчар Ін.М.», «ХарченкоГ.»). The same rule as the
 * web grid (web/src/lib/teacher-name.ts, style {@code grid}).
 */
public final class TeacherNames {

    /** Surname, then one or two initials of one or two letters each («Ін.»). Mirrors core's TeacherNames. */
    private static final Pattern SHORT_FORM =
            Pattern.compile("^(.+?)\\s*((?:\\p{Lu}\\p{Ll}?\\.\\s*){1,2})$");
    private static final Pattern INITIAL = Pattern.compile("\\p{Lu}\\p{Ll}?\\.");

    private TeacherNames() {
    }

    /** «Кренцін М.Д.» → «Кренцін М. Д.»; a short form without initials stays as it is; null stays null. */
    public static String initials(String shortName) {
        if (shortName == null) {
            return null;
        }
        String cleaned = shortName.strip().replaceAll("\\s+", " ");
        Matcher matcher = SHORT_FORM.matcher(cleaned);
        if (!matcher.matches()) {
            return cleaned;
        }
        List<String> parts = new ArrayList<>(3);
        parts.add(matcher.group(1).strip());
        Matcher initial = INITIAL.matcher(matcher.group(2));
        while (initial.find()) {
            parts.add(initial.group());
        }
        return String.join(" ", parts);
    }
}
