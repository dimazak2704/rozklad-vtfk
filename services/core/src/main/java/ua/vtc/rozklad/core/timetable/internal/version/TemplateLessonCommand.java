package ua.vtc.rozklad.core.timetable.internal.version;

import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.Parity;

/** Validated content of a template lesson (create / replace). */
public record TemplateLessonCommand(
        int weekday,
        int pair,
        Parity parity,
        UUID groupId,
        int subgroup,
        UUID disciplineId,
        UUID teacherId,
        UUID roomId,
        LessonKind lessonKind,
        UUID loadItemId,
        boolean pinned,
        boolean online,
        UUID streamId) {

    public TemplateLessonCommand {
        Objects.requireNonNull(parity, "parity");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(disciplineId, "disciplineId");
        Objects.requireNonNull(lessonKind, "lessonKind");
    }
}
