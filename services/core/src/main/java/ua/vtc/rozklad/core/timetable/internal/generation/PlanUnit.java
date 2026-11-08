package ua.vtc.rozklad.core.timetable.internal.generation;

import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;

/**
 * One weekly unit sent to the scheduler together with what core needs to turn its placement into a
 * template lesson (kind, stream, online). Stored in {@code generation_job.plan}.
 *
 * @param sourceLessonId template lesson of the base version a pinned unit comes from, otherwise null
 */
public record PlanUnit(
        String unitId,
        UUID loadItemId,
        UUID groupId,
        int subgroup,
        UUID disciplineId,
        UUID teacherId,
        UnitWeight weight,
        LessonKind lessonKind,
        RoomType requiredRoomType,
        UUID preferredRoomId,
        UUID streamId,
        boolean online,
        ProblemSnapshot.PinnedSlot pinned,
        UUID sourceLessonId) {

    public PlanUnit {
        Objects.requireNonNull(unitId, "unitId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(disciplineId, "disciplineId");
        Objects.requireNonNull(weight, "weight");
        Objects.requireNonNull(lessonKind, "lessonKind");
    }

    /** Pinned at the slot of a base lesson; the unit joins the lesson's stream if it has none of its own. */
    PlanUnit pinnedAt(ProblemSnapshot.PinnedSlot slot, UUID lessonId, UUID lessonStreamId) {
        return new PlanUnit(unitId, loadItemId, groupId, subgroup, disciplineId, teacherId, weight, lessonKind,
                requiredRoomType, preferredRoomId, streamId != null ? streamId : lessonStreamId, online, slot,
                lessonId);
    }

    ProblemSnapshot.Unit toSnapshotUnit(UUID homeRoomId) {
        return new ProblemSnapshot.Unit(unitId, loadItemId, groupId, subgroup, disciplineId, teacherId, weight,
                requiredRoomType, preferredRoomId, homeRoomId, pinned);
    }

    /**
     * The scheduler has no streams: a stream is one lesson of one teacher in one room for several
     * groups. Every unit of a pinned stream but the first is sent without teacher and room, so the
     * solver sees no clash; core gives the generated lesson the real teacher and room back.
     */
    ProblemSnapshot.Unit toStreamFollowerUnit(UUID homeRoomId) {
        ProblemSnapshot.PinnedSlot slot = new ProblemSnapshot.PinnedSlot(pinned.weekday(), pinned.pair(),
                pinned.parity(), null);
        return new ProblemSnapshot.Unit(unitId, loadItemId, groupId, subgroup, disciplineId, null, weight, null, null,
                homeRoomId, slot);
    }

    /**
     * Key of one stream occurrence: the stream and its pinned weekday and pair. Parity is not part of
     * it: groups of one stream may meet in different weeks (e.g. one NUM, one BOTH), and the teacher
     * still takes them in one slot.
     */
    StreamSlot streamSlot() {
        return streamId == null || pinned == null ? null : new StreamSlot(streamId, pinned.weekday(), pinned.pair());
    }

    record StreamSlot(UUID streamId, int weekday, int pair) {
    }
}
