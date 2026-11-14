package ua.vtc.rozklad.notification.text;

import java.util.UUID;

/** How a schedule card writes a teacher it knows by id and short form («Прізвище І. Б.»). */
@FunctionalInterface
public interface TeacherLabels {

    /** Only the short form: «Кренцін М.Д.» → «Кренцін М. Д.», a bare surname stays bare. */
    TeacherLabels SHORT_FORM = (id, shortName) -> TeacherNames.initials(shortName);

    /** {@code id} may be {@code null} (an original lesson written as plain text). */
    String label(UUID id, String shortName);
}
