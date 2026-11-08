package ua.vtc.rozklad.core.timetable.internal.publication;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A dated lesson that survives a republication: created or modified by a change, or referenced by
 * one. {@code scheduled == false} means CANCELLED (e.g. the original of an applied substitution).
 *
 * @param online     the lesson is online now (then it does not occupy its room)
 * @param course     course of the lesson's group (the study mode of a day depends on it)
 * @param sharedRoom the room hosts several lessons at once ({@code max_parallel > 1})
 * @param root       the template lesson the lesson stands for, {@code null} for an extra lesson or a block
 * @param fromOrder  created by a change (otherwise a template lesson kept for a decision about it)
 */
public record KeptLesson(UUID id, LocalDate date, int pair, UUID groupId, int subgroup, UUID teacherId, UUID roomId,
                         boolean roomExclusive, UUID streamId, boolean scheduled, boolean online, int course,
                         boolean sharedRoom, Root root, boolean fromOrder) {

    /** A kept lesson without mode and origin facts (tests, lessons nobody re-checks). */
    public KeptLesson(UUID id, LocalDate date, int pair, UUID groupId, int subgroup, UUID teacherId, UUID roomId,
                      boolean roomExclusive, UUID streamId, boolean scheduled) {
        this(id, date, pair, groupId, subgroup, teacherId, roomId, roomExclusive, streamId, scheduled, false, 0,
                false, null, false);
    }

    /** Same group and the cohorts meet (whole group overlaps every subgroup). */
    boolean coversCohort(UUID otherGroupId, int otherSubgroup) {
        return groupId.equals(otherGroupId) && (subgroup == 0 || otherSubgroup == 0 || subgroup == otherSubgroup);
    }

    boolean inStream(UUID otherStreamId) {
        return streamId != null && streamId.equals(otherStreamId);
    }

    /** Whether the lesson stands for a template lesson of {@code versionId}. */
    boolean rootedIn(UUID versionId) {
        return root != null && root.versionId().equals(versionId);
    }

    /** The same lesson in another study mode: online lessons do not occupy their room (DOMAIN §5). */
    KeptLesson withMode(boolean newOnline) {
        boolean exclusive = !newOnline && !sharedRoom;
        return new KeptLesson(id, date, pair, groupId, subgroup, teacherId, roomId, exclusive, streamId, scheduled,
                newOnline, course, sharedRoom, root, fromOrder);
    }

    /**
     * The template lesson a dated lesson stands for: its own template lesson, or, for a lesson a
     * change created (substitution, room change, swap), the template lesson of the change's
     * original, followed back through earlier changes.
     *
     * @param online the template lesson is online by itself (whatever the calendar says)
     */
    public record Root(UUID templateLessonId, UUID versionId, boolean online) {
    }
}
