package ua.vtc.rozklad.core.timetable.internal.publication;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A dated lesson that survives a republication: created or modified by a change, or referenced by
 * one. {@code scheduled == false} means CANCELLED (e.g. the original of an applied substitution).
 */
public record KeptLesson(UUID id, LocalDate date, int pair, UUID groupId, int subgroup, UUID teacherId, UUID roomId,
                         boolean roomExclusive, UUID streamId, boolean scheduled) {

    /** Same group and the cohorts meet (whole group overlaps every subgroup). */
    boolean coversCohort(UUID otherGroupId, int otherSubgroup) {
        return groupId.equals(otherGroupId) && (subgroup == 0 || otherSubgroup == 0 || subgroup == otherSubgroup);
    }

    boolean inStream(UUID otherStreamId) {
        return streamId != null && streamId.equals(otherStreamId);
    }
}
