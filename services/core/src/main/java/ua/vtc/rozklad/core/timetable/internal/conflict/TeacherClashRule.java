package ua.vtc.rozklad.core.timetable.internal.conflict;

import ua.vtc.rozklad.core.shared.error.ConflictDto.Severity;
import ua.vtc.rozklad.core.shared.error.ConflictKind;

/** A teacher has two lessons at the same time (lessons of one stream are a single lesson). */
final class TeacherClashRule implements ConflictRule {

    @Override
    public void check(LessonIndex index, ConflictFacts facts, ConflictSink sink) {
        index.bySlot().forEach((slot, lessons) -> LessonIndex.byOwner(lessons, SlotLesson::teacherId)
                .forEach((teacherId, own) -> {
                    if (own.size() < 2) {
                        return;
                    }
                    Clash clash = Clash.find(slot.day(), own, (first, second) -> !first.sameStream(second));
                    if (clash.isEmpty()) {
                        return;
                    }
                    String message = "Викладач %s одночасно має кілька занять: %s (%s%s)".formatted(
                            facts.teacherName(teacherId), ConflictText.groupLessons(clash.lessons(), facts),
                            ConflictText.slot(slot.day(), slot.pair()), ConflictText.weeks(slot.day(), clash.weeks()));
                    sink.add(ConflictText.conflict(ConflictKind.TEACHER, Severity.HARD, message,
                            ConflictText.ids(clash.lessons()), slot.day(), slot.pair()));
                }));
    }
}
