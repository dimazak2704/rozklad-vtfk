package ua.vtc.rozklad.core.substitution.internal.effect;

import java.time.LocalDate;
import java.util.UUID;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonOrigin;

/** A dated lesson a change will create (checked for conflicts before it is inserted). */
public record PlannedLesson(
        UUID semesterId,
        LocalDate date,
        int pair,
        UUID groupId,
        int subgroup,
        UUID disciplineId,
        UUID teacherId,
        UUID roomId,
        LessonKind lessonKind,
        boolean online,
        UUID streamId,
        LessonOrigin origin) {

    /** New entity for the change; {@code roomExclusive}: {@code room.maxParallel == 1} and not online. */
    public Lesson toLesson(UUID changeId, boolean roomExclusive) {
        Lesson lesson = new Lesson(semesterId, date, pair, groupId, subgroup, disciplineId, origin);
        lesson.setTeacherId(teacherId);
        lesson.setRoom(roomId, roomExclusive);
        lesson.setLessonKind(lessonKind);
        lesson.setOnline(online);
        lesson.setStreamId(streamId);
        lesson.setChangeId(changeId);
        return lesson;
    }

    /** Whole group (0) overlaps every subgroup; subgroup k overlaps 0 and k (DOMAIN §5). */
    public static boolean cohortsOverlap(int subgroup, int other) {
        return subgroup == 0 || other == 0 || subgroup == other;
    }
}
