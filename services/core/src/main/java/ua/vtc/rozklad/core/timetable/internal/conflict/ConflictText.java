package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import ua.vtc.rozklad.core.shared.error.ConflictDto.Severity;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.shared.text.UkrainianText;

/** Ukrainian fragments of conflict messages and the {@link ConflictDto} factory of the engine. */
final class ConflictText {

    private ConflictText() {
    }

    /** «понеділок» for the template, «19.10.2026» for a date. */
    static String day(LessonDay day) {
        return day.isDated() ? UkrainianText.date(day.date()) : UkrainianText.weekdayLower(day.weekday());
    }

    /** «понеділок, 2 пара». */
    static String slot(LessonDay day, int pair) {
        return day(day) + ", " + pair + " пара";
    }

    /** «, чисельник» when a template conflict happens only in one kind of week, otherwise empty. */
    static String weeks(LessonDay day, Set<WeekType> weeks) {
        if (day.isDated() || weeks.size() != 1) {
            return "";
        }
        return weeks.contains(WeekType.NUMERATOR) ? ", чисельник" : ", знаменник";
    }

    /** «СТП» or «Ін.мова (підгр. 2)». */
    static String lessonInGroup(SlotLesson lesson, ConflictFacts facts) {
        return facts.disciplineName(lesson.disciplineId()) + subgroup(lesson.subgroup());
    }

    /** «4КІ1 СТП» or «4КІ1 Ін.мова (підгр. 2)». */
    static String lessonOfGroup(SlotLesson lesson, ConflictFacts facts) {
        return facts.groupCode(lesson.groupId()) + " " + lessonInGroup(lesson, facts);
    }

    static String subgroup(int subgroup) {
        return subgroup > 0 ? " (підгр. " + subgroup + ")" : "";
    }

    static String joined(Collection<String> parts) {
        return String.join(", ", new LinkedHashSet<>(parts));
    }

    static List<UUID> ids(Collection<SlotLesson> lessons) {
        return lessons.stream().map(SlotLesson::id).distinct().toList();
    }

    /** «4КІ1 СТП, 4КІ2 НДЕКСМ» ordered by group code, subgroup and discipline. */
    static String groupLessons(Collection<SlotLesson> lessons, ConflictFacts facts) {
        return joined(lessons.stream()
                .sorted(Comparator.comparing((SlotLesson lesson) -> facts.groupCode(lesson.groupId()))
                        .thenComparingInt(SlotLesson::subgroup)
                        .thenComparing(lesson -> facts.disciplineName(lesson.disciplineId())))
                .map(lesson -> lessonOfGroup(lesson, facts))
                .collect(Collectors.toList()));
    }

    /** «СТП, Ін.мова (підгр. 2)»: the whole group first, then subgroups. */
    static String cohortLessons(Collection<SlotLesson> lessons, ConflictFacts facts) {
        return joined(lessons.stream()
                .sorted(Comparator.comparingInt(SlotLesson::subgroup)
                        .thenComparing(lesson -> facts.disciplineName(lesson.disciplineId())))
                .map(lesson -> lessonInGroup(lesson, facts))
                .collect(Collectors.toList()));
    }

    /** Dated conflicts carry the date, template conflicts the weekday (CONTRACTS.md {@code Conflict}). */
    static ConflictDto conflict(ConflictKind kind, Severity severity, String message, Collection<UUID> lessonIds,
                                LessonDay day, int pair) {
        return day.isDated()
                ? new ConflictDto(kind, severity, message, List.copyOf(lessonIds), day.date(), null, pair)
                : new ConflictDto(kind, severity, message, List.copyOf(lessonIds), null, day.weekday(), pair);
    }
}
