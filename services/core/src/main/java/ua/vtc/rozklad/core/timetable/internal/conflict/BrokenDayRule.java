package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.List;
import java.util.SortedSet;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.shared.error.ConflictDto.Severity;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.timetable.internal.support.UkrainianText;

/**
 * «Рваний день» of the template: in numerator and denominator weeks the group has a different
 * number of pairs or starts at a different pair. The halves (NUMERATOR / DENOMINATOR lessons)
 * are listed: putting two halves into one slot evens the weeks out.
 */
final class BrokenDayRule implements ConflictRule {

    @Override
    public void check(LessonIndex index, ConflictFacts facts, ConflictSink sink) {
        index.byGroupDay().forEach((key, lessons) -> {
            LessonDay day = key.day();
            if (day.isDated()) {
                return;
            }
            SortedSet<Integer> numerator = DayShape.pairs(DayShape.active(lessons, WeekType.NUMERATOR));
            SortedSet<Integer> denominator = DayShape.pairs(DayShape.active(lessons, WeekType.DENOMINATOR));
            if (!DayShape.isBroken(numerator, denominator)) {
                return;
            }
            List<SlotLesson> halves = lessons.stream().filter(lesson -> lesson.parity() != Parity.BOTH).toList();
            String message = "Рваний день у групи %s (%s): чисельник — %s, знаменник — %s".formatted(
                    facts.groupCode(key.ownerId()), ConflictText.day(day), describe(numerator), describe(denominator));
            sink.add(ConflictText.conflict(ConflictKind.BROKEN_DAY, Severity.SOFT, message,
                    ConflictText.ids(halves.isEmpty() ? lessons : halves), day,
                    DayShape.firstDifference(numerator, denominator)));
        });
    }

    private static String describe(SortedSet<Integer> pairs) {
        return pairs.isEmpty() ? "без пар" : UkrainianText.pairs(pairs.size()) + " з " + pairs.first() + "-ї";
    }
}
