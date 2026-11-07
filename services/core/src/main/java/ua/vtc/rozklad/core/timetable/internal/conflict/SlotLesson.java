package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;

/**
 * A lesson as the conflict engine sees it, built from a template lesson or a dated lesson. Dated
 * lessons always have parity BOTH (a date is a single week).
 *
 * @param subgroup         0 = the whole group
 * @param requiredRoomType room type required by the load item, {@code null} when not required
 */
public record SlotLesson(
        UUID id,
        LessonDay day,
        int pair,
        Parity parity,
        UUID groupId,
        int subgroup,
        UUID disciplineId,
        UUID teacherId,
        UUID roomId,
        UUID streamId,
        RoomType requiredRoomType) {

    public SlotLesson {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(parity, "parity");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(disciplineId, "disciplineId");
        if (pair < 1 || pair > 8) {
            throw new IllegalArgumentException("pair must be 1..8");
        }
        if (subgroup < 0) {
            throw new IllegalArgumentException("subgroup must be >= 0");
        }
        if (day.isDated() && parity != Parity.BOTH) {
            throw new IllegalArgumentException("a dated lesson has parity BOTH");
        }
    }

    public boolean activeIn(WeekType week) {
        return week.includes(parity);
    }

    /** Both lessons belong to the same stream (one lesson for several groups). */
    public boolean sameStream(SlotLesson other) {
        return streamId != null && streamId.equals(other.streamId);
    }

    /** Same group and the cohorts meet: the whole group overlaps every subgroup, k overlaps 0 and k. */
    public boolean cohortOverlaps(SlotLesson other) {
        return groupId.equals(other.groupId)
                && (subgroup == 0 || other.subgroup == 0 || subgroup == other.subgroup);
    }

    /** Copy placed at another slot (drag & drop preview, move). */
    public SlotLesson movedTo(LessonDay newDay, int newPair, Parity newParity, UUID newRoomId) {
        return new SlotLesson(id, newDay, newPair, newParity, groupId, subgroup, disciplineId, teacherId, newRoomId,
                streamId, requiredRoomType);
    }
}
