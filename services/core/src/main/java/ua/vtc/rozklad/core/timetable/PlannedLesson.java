package ua.vtc.rozklad.core.timetable;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A dated lesson that is about to be created or changed (substitution, extra pair, swap), checked
 * with {@link LessonConflictChecker#preview}. Use the id of an existing lesson when it is changed,
 * a new id otherwise. Nullable: teacherId, roomId, streamId.
 */
public record PlannedLesson(
        UUID id,
        LocalDate date,
        int pair,
        UUID groupId,
        int subgroup,
        UUID disciplineId,
        UUID teacherId,
        UUID roomId,
        UUID streamId) {

    public PlannedLesson {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(disciplineId, "disciplineId");
        if (pair < 1 || pair > 8) {
            throw new IllegalArgumentException("pair must be 1..8");
        }
        if (subgroup < 0) {
            throw new IllegalArgumentException("subgroup must be >= 0");
        }
    }
}
