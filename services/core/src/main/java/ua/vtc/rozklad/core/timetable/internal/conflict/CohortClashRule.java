package ua.vtc.rozklad.core.timetable.internal.conflict;

import ua.vtc.rozklad.core.shared.error.ConflictDto.Severity;
import ua.vtc.rozklad.core.shared.error.ConflictKind;

/**
 * A group cohort has two lessons at the same time: the whole group (subgroup 0) overlaps every
 * subgroup, subgroup k overlaps the whole group and k; subgroups 1 and 2 may study in parallel.
 */
final class CohortClashRule implements ConflictRule {

    @Override
    public void check(LessonIndex index, ConflictFacts facts, ConflictSink sink) {
        index.bySlot().forEach((slot, lessons) -> LessonIndex.byOwner(lessons, SlotLesson::groupId)
                .forEach((groupId, own) -> {
                    if (own.size() < 2) {
                        return;
                    }
                    Clash clash = Clash.find(slot.day(), own, SlotLesson::cohortOverlaps);
                    if (clash.isEmpty()) {
                        return;
                    }
                    String message = "Група %s одночасно має кілька занять: %s (%s%s)".formatted(
                            facts.groupCode(groupId), ConflictText.cohortLessons(clash.lessons(), facts),
                            ConflictText.slot(slot.day(), slot.pair()),
                            ConflictText.weeks(slot.day(), clash.weeks()));
                    sink.add(ConflictText.conflict(ConflictKind.GROUP, Severity.HARD, message,
                            ConflictText.ids(clash.lessons()), slot.day(), slot.pair()));
                }));
    }
}
