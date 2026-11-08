package ua.vtc.rozklad.core.timetable.internal.version;

import java.util.UUID;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.catalog.DisciplineRef;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.RoomRef;
import ua.vtc.rozklad.core.catalog.TeacherRef;

/** {@code TemplateLessonDto} of CONTRACTS.md §2. */
public record TemplateLessonDto(
        UUID id,
        int weekday,
        int pair,
        Parity parity,
        GroupRef group,
        int subgroup,
        DisciplineRef discipline,
        TeacherRef teacher,
        RoomRef room,
        LessonKind lessonKind,
        UUID loadItemId,
        boolean pinned,
        boolean online,
        UUID streamId,
        long version) {
}
