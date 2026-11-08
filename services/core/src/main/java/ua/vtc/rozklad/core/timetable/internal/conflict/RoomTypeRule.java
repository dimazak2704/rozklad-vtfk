package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.List;
import ua.vtc.rozklad.core.shared.error.ConflictDto.Severity;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.shared.text.UkrainianText;

/** The load item requires a room type (e.g. a computer lab) and the lesson is in another type of room. */
final class RoomTypeRule implements ConflictRule {

    @Override
    public void check(LessonIndex index, ConflictFacts facts, ConflictSink sink) {
        index.bySlot().forEach((slot, lessons) -> lessons.forEach(lesson -> {
            RoomFact room = facts.room(lesson.roomId());
            if (lesson.requiredRoomType() == null || room == null || room.type() == lesson.requiredRoomType()) {
                return;
            }
            String message = "Для %s потрібен тип приміщення «%s», а %s має тип «%s» (%s)".formatted(
                    ConflictText.lessonOfGroup(lesson, facts), UkrainianText.roomType(lesson.requiredRoomType()),
                    room.name(), UkrainianText.roomType(room.type()), ConflictText.slot(slot.day(), slot.pair()));
            sink.add(ConflictText.conflict(ConflictKind.ROOM_TYPE, Severity.HARD, message, List.of(lesson.id()),
                    slot.day(), slot.pair()));
        }));
    }
}
