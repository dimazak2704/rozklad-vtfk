package ua.vtc.rozklad.core.substitution.internal.view;

import java.time.LocalTime;
import java.util.UUID;
import ua.vtc.rozklad.core.shared.ref.DisciplineRef;
import ua.vtc.rozklad.core.shared.ref.GroupRef;
import ua.vtc.rozklad.core.shared.ref.RoomRef;
import ua.vtc.rozklad.core.shared.ref.TeacherRef;

/**
 * {@code LessonView} of CONTRACTS.md §1. Times are {@code null} when no bell profile is known;
 * {@code original}, {@code reasonText} and {@code changeId} are set for lessons created by a change.
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
        Kind kind,
        boolean online,
        Original original,
        String reasonText,
        UUID changeId) {

    public record Original(DisciplineRef discipline, TeacherRef teacher, RoomRef room, Integer pair) {
    }

    public enum Kind {
        REGULAR,
        SUBSTITUTED,
        CANCELLED,
        ADDITIONAL,
        MOVED,
        ROOM_CHANGED,
        BLOCK
    }
}
