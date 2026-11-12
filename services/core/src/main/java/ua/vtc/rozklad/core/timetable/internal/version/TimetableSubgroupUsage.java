package ua.vtc.rozklad.core.timetable.internal.version;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.catalog.SubgroupUsage;
import ua.vtc.rozklad.core.timetable.LessonRepository;
import ua.vtc.rozklad.core.timetable.TemplateLessonRepository;

/**
 * A subgroup taught in a timetable version (any status: an archived version still governs its dates)
 * or in a dated lesson from today on is in use, so the catalog refuses to delete or renumber it.
 * Past dated lessons are history and do not block it.
 */
@Component
class TimetableSubgroupUsage implements SubgroupUsage {

    private final TemplateLessonRepository templateLessons;
    private final LessonRepository lessons;
    private final Clock clock;

    TimetableSubgroupUsage(TemplateLessonRepository templateLessons, LessonRepository lessons, Clock clock) {
        this.templateLessons = templateLessons;
        this.lessons = lessons;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> usagesOf(UUID groupId, int subgroupNumber) {
        List<String> usages = new ArrayList<>(2);
        long template = templateLessons.countByGroupIdAndSubgroupNumber(groupId, subgroupNumber);
        if (template > 0) {
            usages.add("розклад (версії), занять: %d".formatted(template));
        }
        long dated = lessons.countByGroupIdAndSubgroupNumberAndDateGreaterThanEqual(groupId, subgroupNumber,
                LocalDate.now(clock));
        if (dated > 0) {
            usages.add("розклад на дати від сьогодні, занять: %d".formatted(dated));
        }
        return usages;
    }
}
