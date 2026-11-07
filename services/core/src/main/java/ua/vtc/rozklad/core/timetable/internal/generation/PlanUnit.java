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

    PlanUnit pinnedAt(ProblemSnapshot.PinnedSlot slot, UUID lessonId) {
        return new PlanUnit(unitId, loadItemId, groupId, subgroup, disciplineId, teacherId, weight, lessonKind,
                requiredRoomType, preferredRoomId, streamId, online, slot, lessonId);
    }

    ProblemSnapshot.Unit toSnapshotUnit(UUID homeRoomId) {
        return new ProblemSnapshot.Unit(unitId, loadItemId, groupId, subgroup, disciplineId, teacherId, weight,
                requiredRoomType, preferredRoomId, homeRoomId, pinned);
    }
}
