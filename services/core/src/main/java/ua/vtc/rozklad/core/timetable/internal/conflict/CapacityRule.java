package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.core.shared.error.ConflictDto.Severity;
import ua.vtc.rozklad.core.shared.error.ConflictKind;

/**
 * The room has fewer seats than the cohort (a stream sums its groups). Unknown capacity or size
 * skips the check: imported data often lacks them and DOMAIN.md forbids inventing values.
 */
final class CapacityRule implements ConflictRule {

    @Override
    public void check(LessonIndex index, ConflictFacts facts, ConflictSink sink) {
        index.bySlot().forEach((slot, lessons) -> LessonIndex.byOwner(lessons, SlotLesson::roomId)
                .forEach((roomId, own) -> {
                    RoomFact room = facts.room(roomId);
                    if (room == null || room.capacity() == null) {
                        return;
                    }
                    LessonIndex.byOwner(own, CapacityRule::occupancy).values()
                            .forEach(occupancy -> checkOccupancy(slot, room, occupancy, facts, sink));
                }));
    }

    private static UUID occupancy(SlotLesson lesson) {
        return lesson.streamId() != null ? lesson.streamId() : lesson.id();
    }

    private static void checkOccupancy(LessonIndex.SlotKey slot, RoomFact room, List<SlotLesson> occupancy,
                                       ConflictFacts facts, ConflictSink sink) {
        int students = 0;
        for (SlotLesson lesson : occupancy) {
            GroupFact group = facts.group(lesson.groupId());
            Integer size = group == null ? null : group.cohortSize(lesson.subgroup());
            if (size == null) {
                return;
            }
            students += size;
        }
        if (students <= room.capacity()) {
            return;
        }
        String message = "Аудиторія %s замала для %s: місткість — %d, студентів — %d (%s)".formatted(room.name(),
                ConflictText.groupLessons(occupancy, facts), room.capacity(), students,
                ConflictText.slot(slot.day(), slot.pair()));
        sink.add(ConflictText.conflict(ConflictKind.CAPACITY, Severity.HARD, message, ConflictText.ids(occupancy),
                slot.day(), slot.pair()));
    }
}
