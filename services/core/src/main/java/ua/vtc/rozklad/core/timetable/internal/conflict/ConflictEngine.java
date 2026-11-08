package ua.vtc.rozklad.core.timetable.internal.conflict;

import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The one conflict engine of DOMAIN.md §5 for both the weekly template (weekday × pair × parity)
 * and dated lessons (date × pair). Hard: teacher, cohort and room clashes (streams, maxParallel),
 * capacity, room type, shift window (template only), teacher UNAVAILABLE. Soft: windows of groups and teachers,
 * «рваний день», more than four pairs a day, a discipline three times a day, UNWANTED slots.
 * Stateless and thread-safe.
 */
@Component
public class ConflictEngine {

    private final List<ConflictRule> rules = List.of(
            new TeacherClashRule(),
            new CohortClashRule(),
            new RoomOccupancyRule(),
            new CapacityRule(),
            new RoomTypeRule(),
            new ShiftWindowRule(),
            new TeacherPreferenceRule(),
            new GroupWindowRule(),
            new TeacherWindowRule(),
            new BrokenDayRule(),
            new DayOverloadRule(),
            new DisciplineRepeatRule());

    public ConflictReport check(Collection<SlotLesson> lessons, ConflictFacts facts) {
        LessonIndex index = LessonIndex.of(lessons);
        ConflictSink sink = new ConflictSink();
        rules.forEach(rule -> rule.check(index, facts, sink));
        return sink.report();
    }
}
