package ua.vtc.rozklad.core.timetable.internal.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import ua.vtc.rozklad.core.timetable.DisciplineRef;
import ua.vtc.rozklad.core.timetable.GroupRef;
import ua.vtc.rozklad.core.timetable.LessonOrigin;
import ua.vtc.rozklad.core.timetable.LessonStatus;
import ua.vtc.rozklad.core.timetable.RoomRef;
import ua.vtc.rozklad.core.timetable.TeacherRef;

/**
 * A dated lesson joined with its catalog references and the change that created, modified or
 * cancelled it (change columns are {@code null} for a lesson of the base timetable).
 */
record LessonRow(
        UUID id,
        LocalDate date,
        int pair,
        int subgroup,
        LessonStatus status,
        LessonOrigin origin,
        boolean online,
        Instant updatedAt,
        GroupRef group,
        DisciplineRef discipline,
        TeacherRef teacher,
        RoomRef room,
        Change change) {

    boolean scheduled() {
        return status == LessonStatus.SCHEDULED;
    }

    UUID changeId() {
        return change == null ? null : change.id();
    }

    /**
     * Change columns: kind and reason as stored by the substitution module, the original lesson's
     * discipline, teacher and room, and its pair ({@code originalPair}).
     */
    record Change(UUID id, String kind, String reasonCode, String reasonText, int pairFrom, int pairTo,
                  DisciplineRef originalDiscipline, TeacherRef originalTeacher, RoomRef originalRoom,
                  Integer originalPair) {
    }
}
