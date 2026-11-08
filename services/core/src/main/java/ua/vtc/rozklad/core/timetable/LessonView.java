package ua.vtc.rozklad.core.timetable;

import java.time.LocalTime;
import java.util.UUID;
import ua.vtc.rozklad.core.catalog.DisciplineRef;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.RoomRef;
import ua.vtc.rozklad.core.catalog.TeacherRef;

/**
 * {@code LessonView} of CONTRACTS.md §1: a dated lesson as students and teachers see it.
 * {@code original} carries what the applied change replaced (taken from the change row), and
 * {@code reasonText} the short public reason printed in orders («Відп.», «Зв.зам.» …).
 *
 * @param subgroup 0 = the whole group, 1..n = subgroup
 */
public record LessonView(
        UUID id,
        int pair,
        LocalTime startsAt,
        LocalTime endsAt,
        DisciplineRef discipline,
        TeacherRef teacher,
        RoomRef room,
        GroupRef group,
        int subgroup,
        LessonViewKind kind,
        boolean online,
        Original original,
        String reasonText,
        UUID changeId) {

    /** The replaced lesson: every known field of the original plus its pair when the lesson was moved. */
    public record Original(DisciplineRef discipline, TeacherRef teacher, RoomRef room, Integer pair) {
    }
}
