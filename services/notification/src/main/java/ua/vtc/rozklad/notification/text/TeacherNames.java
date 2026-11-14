package ua.vtc.rozklad.notification.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    private static final Locale UK = Locale.forLanguageTag("uk");

    private TeacherNames() {
    }

    /** «Кренцін М.Д.» → «Кренцін М. Д.»; a short form without initials stays as it is; null stays null. */
    public static String initials(String shortName) {
        return initials(shortName, null, null);
    }

    /**
     * Like {@link #initials(String)}, completed from the public full name «Прізвище Імʼя По батькові» and the
     * first name when they give more initials than the short form («Алексєєнко» → «Алексєєнко О. А.»,
     * «Колесник А.» → «Колесник А. І.»). Two-letter initials of namesakes («Овчар Ін.М.») always stay.
     */
    public static String initials(String shortName, String fullName, String firstName) {
        if (shortName == null) {
            return null;
        }
        String cleaned = clean(shortName);
        Matcher matcher = SHORT_FORM.matcher(cleaned);
        String surname = matcher.matches() ? matcher.group(1).strip() : cleaned;
        List<String> fromShort = new ArrayList<>(2);
        if (matcher.matches()) {
            Matcher initial = INITIAL.matcher(matcher.group(2));
            while (initial.find()) {
                fromShort.add(initial.group());
            }
        }
        List<String> chosen = fromShort;
        if (fromShort.stream().noneMatch(initial -> initial.length() > 2)) {
            List<String> fromNames = fromFullName(fullName, firstName);
            if (fromNames.size() > fromShort.size()) {
                chosen = fromNames;
            }
        }
        return chosen.isEmpty() ? surname : surname + " " + String.join(" ", chosen);
    }

    /** Initials of the first name and the patronymic (the words after the first name in the full name). */
    private static List<String> fromFullName(String fullName, String firstName) {
        if (fullName == null || firstName == null || fullName.isBlank() || firstName.isBlank()) {
            return List.of();
        }
        String first = clean(firstName);
        List<String> words = List.of(clean(fullName).split(" "));
        int at = words.subList(1, words.size()).indexOf(first);
        List<String> out = new ArrayList<>(2);
        out.add(initialOf(first));
        if (at >= 0 && at + 2 < words.size()) {
            out.add(initialOf(words.get(at + 2)));
        }
        return out;
    }

    private static String initialOf(String name) {
        return name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(UK) + ".";
    }

    private static String clean(String text) {
        return text.strip().replaceAll("\\s+", " ");
    }
}
