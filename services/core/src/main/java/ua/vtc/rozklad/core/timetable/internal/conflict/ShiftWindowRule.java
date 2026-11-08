package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.List;
import ua.vtc.rozklad.core.shared.error.ConflictDto.Severity;
import ua.vtc.rozklad.core.shared.error.ConflictKind;

/**
 * The lesson of the weekly template is outside the shift window of its group (1st shift: pairs 1–4,
 * 2nd: 5–8 / 4–6 on Friday). The window is a rule of the regular timetable only (editor, generator,
 * version publication): dated lessons are either copies of the checked template or made by changes
 * and orders, and a substitution or an extra pair may be put on any pair 1–8 (DOMAIN §5, §7).
 */
final class ShiftWindowRule implements ConflictRule {

    @Override
    public void check(LessonIndex index, ConflictFacts facts, ConflictSink sink) {
        index.bySlot().forEach((slot, lessons) -> lessons.forEach(lesson -> {
            if (slot.day().isDated()) {
                return;
            }
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
