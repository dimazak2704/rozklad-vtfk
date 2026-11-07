package ua.vtc.rozklad.importer.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A teacher name as written in college documents: surname plus optional initials («Вихристюк»,
 * «Ткач Ж.», «Овчар Ін.», «Харченко О.А.»). Parsing fixes the formatting typos of the real Excel
 * («ХарченкоГ.», «Крисак Л .», double spaces) and strips the markers «(п)» (one subgroup) and
 * «(гр)» / «гр.» (whole group).
 *
 * @param surname  surname as written (apostrophes kept)
 * @param initials initial groups without dots: «Ю.А.» → [Ю, А], «Ін.» → [Ін]
 */
public record PersonName(String surname, List<String> initials, boolean subgroupMarker, boolean wholeGroupMarker) {

    // маркери пишуть малими літерами, тож вони не плутаються з ініціалами «П.» чи «Г.»
    private static final Pattern SUBGROUP_MARKER = Pattern.compile("\\(\\s*п\\s*\\.?\\s*\\)\\.?");
    // не \b: з JDK 19 він рахує словом лише ASCII, тож біля кирилиці межу не бачить
    private static final Pattern WHOLE_GROUP_MARKER = Pattern.compile("\\(?\\s*(?<!\\p{L})гр\\s*\\.?\\s*\\)?\\.?(?=\\s|$)");
    private static final Pattern GLUED_INITIAL = Pattern.compile("([а-яіїєґ'’ʼ])([А-ЯІЇЄҐ][а-яіїєґ]?\\.)");
    private static final Pattern SPACE_BEFORE_DOT = Pattern.compile("\\s+\\.");
    private static final Pattern INITIAL = Pattern.compile("([А-ЯІЇЄҐA-Z][а-яіїєґa-z]?)\\.?");

    public PersonName {
        initials = List.copyOf(initials);
    }

    /** Parses a single name (no «/»); returns {@code null} for blank text. */
    public static PersonName parse(String raw) {
        String text = TextNormalizer.clean(raw);
        if (text.isEmpty()) {
            return null;
        }
        boolean subgroup = false;
        Matcher sub = SUBGROUP_MARKER.matcher(text);
        if (sub.find()) {
            subgroup = true;
            text = sub.replaceAll(" ");
        }
        boolean wholeGroup = false;
        Matcher whole = WHOLE_GROUP_MARKER.matcher(text);
        if (whole.find()) {
            wholeGroup = true;
            text = whole.replaceAll(" ");
        }
        text = GLUED_INITIAL.matcher(text).replaceAll("$1 $2");
        text = SPACE_BEFORE_DOT.matcher(text).replaceAll(".");
        text = TextNormalizer.clean(text);
        if (text.isEmpty()) {
            return null;
        }
        int space = text.indexOf(' ');
        String surname = space < 0 ? text : text.substring(0, space);
        String rest = space < 0 ? "" : text.substring(space + 1).replace(" ", "");
        // «Іванов.» — крапка після прізвища без ініціалів
        if (surname.endsWith(".") && rest.isEmpty() && surname.length() > 3) {
            surname = surname.substring(0, surname.length() - 1);
        }
        return new PersonName(surname, parseInitials(rest), subgroup, wholeGroup);
    }

    private static List<String> parseInitials(String text) {
        List<String> result = new ArrayList<>();
        Matcher matcher = INITIAL.matcher(text);
        int position = 0;
        while (position < text.length() && matcher.find(position) && matcher.start() == position) {
            result.add(matcher.group(1));
            position = matcher.end();
        }
        return result;
    }

    /** Comparison key of the surname. */
    public String surnameKey() {
        return TextNormalizer.key(surname);
    }

    public boolean hasInitials() {
        return !initials.isEmpty();
    }

    /** «Ю.А.» (empty when there are no initials). */
    public String formattedInitials() {
        StringBuilder result = new StringBuilder();
        for (String initial : initials) {
            result.append(initial).append('.');
        }
        return result.toString();
    }

    /** «Харченко О.А.» or just the surname. */
    public String shortForm() {
        return hasInitials() ? surname + " " + formattedInitials() : surname;
    }

    /**
     * Initials do not contradict each other: every pair of corresponding groups is a prefix of the
     * other («О.» ~ «О.А.», «І» ~ «Ін»), missing groups are compatible; «Ін.» ≠ «Ів.».
     */
    public boolean initialsCompatibleWith(PersonName other) {
        int common = Math.min(initials.size(), other.initials.size());
        for (int i = 0; i < common; i++) {
            String a = initials.get(i).toLowerCase(TextNormalizer.UK);
            String b = other.initials.get(i).toLowerCase(TextNormalizer.UK);
            if (!a.startsWith(b) && !b.startsWith(a)) {
                return false;
            }
        }
        return true;
    }

    /** Same surname key and compatible initials. */
    public boolean sameSurnameAndCompatible(PersonName other) {
        return surnameKey().equals(other.surnameKey()) && initialsCompatibleWith(other);
    }

    /** This name has at least the initials of {@code other} and more. */
    public boolean moreSpecificThan(PersonName other) {
        return initials.size() > other.initials.size();
    }

    @Override
    public String toString() {
        return shortForm();
    }
}
