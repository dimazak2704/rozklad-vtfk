package ua.vtc.rozklad.core.timetable.internal.publication;

import java.time.LocalDate;
import java.util.UUID;
import ua.vtc.rozklad.contracts.LessonKind;

/** A dated lesson materialized from a template lesson (origin TEMPLATE, status SCHEDULED). */
public record NewLesson(
        UUID id,
        UUID semesterId,
        UUID versionId,
        UUID templateLessonId,
        LocalDate date,
        int pair,
        UUID groupId,
        int subgroup,
        UUID disciplineId,
        UUID teacherId,
        UUID roomId,
        boolean roomExclusive,
        UUID streamId,
        LessonKind lessonKind,
        boolean online) {
}
