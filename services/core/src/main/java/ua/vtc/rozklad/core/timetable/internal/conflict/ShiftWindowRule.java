package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.List;
import ua.vtc.rozklad.core.shared.error.ConflictDto.Severity;
import ua.vtc.rozklad.core.shared.error.ConflictKind;

/** The lesson is outside the shift window of its group (1st shift: pairs 1–4, 2nd: 5–8 / 4–6 on Friday). */
final class ShiftWindowRule implements ConflictRule {

    @Override
    public void check(LessonIndex index, ConflictFacts facts, ConflictSink sink) {
        index.bySlot().forEach((slot, lessons) -> lessons.forEach(lesson -> {
            GroupFact group = facts.group(lesson.groupId());
            if (group == null) {
                return;
            }
            facts.shiftWindows().window(group.shift(), slot.day().weekday())
                    .filter(window -> !window.contains(slot.pair()))
                    .ifPresent(window -> {
                        String message = "Група %s навчається в %d зміну (%s: пари %d–%d), а %s стоїть на %d парі"
                                .formatted(group.code(), group.shift(), ConflictText.day(slot.day()), window.first(),
                                        window.last(), ConflictText.lessonInGroup(lesson, facts), slot.pair());
                        sink.add(ConflictText.conflict(ConflictKind.SHIFT, Severity.HARD, message,
                                List.of(lesson.id()), slot.day(), slot.pair()));
                    });
        }));
    }
}
