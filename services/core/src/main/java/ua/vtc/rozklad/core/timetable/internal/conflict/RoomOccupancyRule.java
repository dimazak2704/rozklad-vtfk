package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import ua.vtc.rozklad.core.shared.error.ConflictDto.Severity;
import ua.vtc.rozklad.core.shared.error.ConflictKind;

/**
 * A room hosts more lessons at once than it allows: one for a usual room, {@code maxParallel} for a
 * sports hall. The lessons of one stream occupy the room once.
 */
final class RoomOccupancyRule implements ConflictRule {

    @Override
    public void check(LessonIndex index, ConflictFacts facts, ConflictSink sink) {
        index.bySlot().forEach((slot, lessons) -> LessonIndex.byOwner(lessons, SlotLesson::roomId)
                .forEach((roomId, own) -> {
                    RoomFact room = facts.room(roomId);
                    int allowed = room == null ? 1 : room.maxParallel();
                    Set<SlotLesson> involved = new LinkedHashSet<>();
                    Set<WeekType> weeks = EnumSet.noneOf(WeekType.class);
                    long planned = 0;
                    for (WeekType week : slot.day().weeks()) {
                        List<SlotLesson> active = DayShape.active(own, week);
                        long occupancies = active.stream()
                                .map(lesson -> lesson.streamId() != null ? lesson.streamId() : lesson.id())
                                .distinct()
                                .count();
                        if (occupancies > allowed) {
                            involved.addAll(active);
                            weeks.add(week);
                            planned = Math.max(planned, occupancies);
                        }
                    }
                    if (involved.isEmpty()) {
                        return;
                    }
                    List<SlotLesson> clashing = own.stream().filter(involved::contains).toList();
                    String where = ConflictText.slot(slot.day(), slot.pair()) + ConflictText.weeks(slot.day(), weeks);
                    String message = allowed == 1
                            ? "Аудиторія %s одночасно зайнята кількома заняттями: %s (%s)".formatted(
                                    facts.roomName(roomId), ConflictText.groupLessons(clashing, facts), where)
                            : "Аудиторія %s приймає до %d груп одночасно, а заплановано %d: %s (%s)".formatted(
                                    facts.roomName(roomId), allowed, planned,
                                    ConflictText.groupLessons(clashing, facts), where);
                    sink.add(ConflictText.conflict(ConflictKind.ROOM, Severity.HARD, message,
                            ConflictText.ids(clashing), slot.day(), slot.pair()));
                }));
    }
}
