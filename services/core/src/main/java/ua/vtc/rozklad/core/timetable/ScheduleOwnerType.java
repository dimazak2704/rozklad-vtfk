package ua.vtc.rozklad.core.timetable;

import java.util.Locale;
import java.util.Optional;

/** Whose schedule is shown: a group, a teacher or a room ({@code /api/public/schedule/{type}/{id}}). */
public enum ScheduleOwnerType {
    GROUP,
    TEACHER,
    ROOM;

    /** Path segment {@code group|teacher|room} (case-insensitive). */
    public static Optional<ScheduleOwnerType> fromPathSegment(String segment) {
        if (segment == null) {
            return Optional.empty();
        }
        return switch (segment.toLowerCase(Locale.ROOT)) {
            case "group" -> Optional.of(GROUP);
            case "teacher" -> Optional.of(TEACHER);
            case "room" -> Optional.of(ROOM);
            default -> Optional.empty();
        };
    }
}
