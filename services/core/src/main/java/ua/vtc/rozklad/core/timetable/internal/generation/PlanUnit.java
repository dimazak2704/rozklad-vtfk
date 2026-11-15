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
 * @param initial        (additive) where a free unit stands in the base version: the warm start and the
 *                       baseline of «Переставлено N з M пар»; {@code null} for pinned units, units without a
 *                       base lesson and plans stored before it
 * @param pinnedInBase   (additive) whether the base lesson of a pinned unit was pinned by the dispatcher.
 *                       Out-of-scope and stream lessons are sent pinned so the solver works around them, but
 *                       the draft pins only what the base pinned (B1). {@code null} in plans stored before it:
 *                       then every pinned unit stays pinned, as before
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
        UUID sourceLessonId,
        ProblemSnapshot.PinnedSlot initial,
        Boolean pinnedInBase) {

    public PlanUnit {
        Objects.requireNonNull(unitId, "unitId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(disciplineId, "disciplineId");
        Objects.requireNonNull(weight, "weight");
        Objects.requireNonNull(lessonKind, "lessonKind");
    }

    /** A unit without a base placement (the shape of plans stored before {@code initial} and {@code pinnedInBase}). */
    public PlanUnit(String unitId, UUID loadItemId, UUID groupId, int subgroup, UUID disciplineId, UUID teacherId,
                    UnitWeight weight, LessonKind lessonKind, RoomType requiredRoomType, UUID preferredRoomId,
                    UUID streamId, boolean online, ProblemSnapshot.PinnedSlot pinned, UUID sourceLessonId) {
        this(unitId, loadItemId, groupId, subgroup, disciplineId, teacherId, weight, lessonKind, requiredRoomType,
                preferredRoomId, streamId, online, pinned, sourceLessonId, null, null);
    }

    /** Pinned at the slot of a base lesson; the unit joins the lesson's stream if it has none of its own. */
    PlanUnit pinnedAt(ProblemSnapshot.PinnedSlot slot, UUID lessonId, UUID lessonStreamId) {
        return pinnedAt(slot, lessonId, lessonStreamId, online);
    }

    /** As {@link #pinnedAt(ProblemSnapshot.PinnedSlot, UUID, UUID)}, taking the pinned lesson's online flag. */
    PlanUnit pinnedAt(ProblemSnapshot.PinnedSlot slot, UUID lessonId, UUID lessonStreamId, boolean lessonOnline) {
        return pinnedAt(slot, lessonId, lessonStreamId, lessonOnline, true);
    }

    /**
     * Pinned at the slot of a base lesson, recording whether the dispatcher pinned that lesson: a stream
     * lesson is sent pinned because the solver has no streams, but its draft lesson stays free.
     */
    PlanUnit pinnedAt(ProblemSnapshot.PinnedSlot slot, UUID lessonId, UUID lessonStreamId, boolean lessonOnline,
                      boolean lessonPinned) {
        return new PlanUnit(unitId, loadItemId, groupId, subgroup, disciplineId, teacherId, weight, lessonKind,
                requiredRoomType, preferredRoomId, streamId != null ? streamId : lessonStreamId, lessonOnline, slot,
                lessonId, null, lessonPinned);
    }

    /** The same unit taught by the base lesson's teacher (B3: generation never changes who teaches). */
    PlanUnit withTeacher(UUID baseTeacherId) {
        return new PlanUnit(unitId, loadItemId, groupId, subgroup, disciplineId, baseTeacherId, weight, lessonKind,
                requiredRoomType, preferredRoomId, streamId, online, pinned, sourceLessonId, initial, pinnedInBase);
    }

    /** A free unit that starts at the slot of its base lesson. */
    PlanUnit startingAt(ProblemSnapshot.PinnedSlot baseSlot) {
        return new PlanUnit(unitId, loadItemId, groupId, subgroup, disciplineId, teacherId, weight, lessonKind,
                requiredRoomType, preferredRoomId, streamId, online, pinned, sourceLessonId, baseSlot, pinnedInBase);
    }

    /**
     * Whether the generated lesson is pinned: only when the dispatcher pinned its base lesson (B1). Plans
     * stored before {@code pinnedInBase} keep the old rule (every pinned unit).
     */
    boolean pinnedInDraft() {
        return pinned != null && (pinnedInBase == null || pinnedInBase);
    }

    /**
     * A pinned online lesson is sent with {@code online}: its room is kept for reference and occupies
     * nothing (DOMAIN §5). Free units are always sent in person.
     *
     * @param teacherRoomId the teacher's usual room for the discipline, or {@code null}
     * @param initial       where the unit stands in the base version (a warm start), or {@code null}
     */
    ProblemSnapshot.Unit toSnapshotUnit(UUID teacherRoomId, ProblemSnapshot.PinnedSlot initial) {
        return toSnapshotUnit(teacherRoomId, initial, null);
    }

    /**
     * As {@link #toSnapshotUnit(UUID, ProblemSnapshot.PinnedSlot)} with the sibling key shared by the
     * subgroup copies of one weekly occurrence of a split item; a pinned unit never has one.
     */
    ProblemSnapshot.Unit toSnapshotUnit(UUID teacherRoomId, ProblemSnapshot.PinnedSlot initial, String siblingKey) {
        return new ProblemSnapshot.Unit(unitId, loadItemId, groupId, subgroup, disciplineId, teacherId, weight,
                requiredRoomType, preferredRoomId, null, pinned, pinned == null ? initial : null,
                pinned == null ? teacherRoomId : null, pinned != null && online, pinned == null ? siblingKey : null);
    }

    /**
     * The scheduler has no streams: a stream is one lesson of one teacher in one room for several
     * groups. Every unit of a pinned stream but the first is sent without teacher and room, so the
     * solver sees no clash; core gives the generated lesson the real teacher and room back.
     */
    ProblemSnapshot.Unit toStreamFollowerUnit() {
        ProblemSnapshot.PinnedSlot slot = new ProblemSnapshot.PinnedSlot(pinned.weekday(), pinned.pair(),
                pinned.parity(), null);
        return new ProblemSnapshot.Unit(unitId, loadItemId, groupId, subgroup, disciplineId, null, weight, null, null,
                null, slot);
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
