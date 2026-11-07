package ua.vtc.rozklad.core.timetable.internal.conflict;

import ua.vtc.rozklad.core.shared.error.ConflictDto.Severity;
import ua.vtc.rozklad.core.shared.error.ConflictKind;

/** A lesson in a slot the teacher marked UNAVAILABLE (hard) or UNWANTED (soft). */
final class TeacherPreferenceRule implements ConflictRule {

    @Override
    public void check(LessonIndex index, ConflictFacts facts, ConflictSink sink) {
        index.bySlot().forEach((slot, lessons) -> LessonIndex.byOwner(lessons, SlotLesson::teacherId)
                .forEach((teacherId, own) -> facts.preferences()
                        .of(teacherId, slot.day().weekday(), slot.pair())
                        .ifPresent(preference -> {
                            String where = "%s (%s)".formatted(ConflictText.groupLessons(own, facts),
                                    ConflictText.slot(slot.day(), slot.pair()));
                            boolean unavailable = preference == SlotPreference.UNAVAILABLE;
                            String message = unavailable
                                    ? "Викладач %s недоступний у цей час: %s".formatted(facts.teacherName(teacherId),
                                            where)
                                    : "Небажаний час для викладача %s: %s".formatted(facts.teacherName(teacherId),
                                            where);
                            sink.add(ConflictText.conflict(
                                    unavailable ? ConflictKind.UNAVAILABLE : ConflictKind.UNWANTED,
                                    unavailable ? Severity.HARD : Severity.SOFT, message, ConflictText.ids(own),
                                    slot.day(), slot.pair()));
                        })));
    }
}
