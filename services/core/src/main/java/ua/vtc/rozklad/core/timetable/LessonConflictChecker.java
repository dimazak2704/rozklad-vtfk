package ua.vtc.rozklad.core.timetable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.core.shared.error.ConflictDto;

/**
 * The conflict engine of DOMAIN.md §5 applied to dated lessons (the same rules as for the weekly
 * template): teacher, cohort and room clashes, capacity, room type, shift window, teacher
 * availability — and the soft warnings (windows, overload, repeated discipline, unwanted slots).
 * Only SCHEDULED lessons take part. Conflicts carry {@code date} and Ukrainian messages.
 */
public interface LessonConflictChecker {

    /** Hard conflicts and soft warnings that involve any of the given lessons on their dates. */
    List<ConflictDto> conflictsOf(Collection<UUID> lessonIds);

    /**
     * What the planned lessons would conflict with, given the SCHEDULED lessons of their dates.
     * Lessons in {@code ignoredLessonIds} are left out (e.g. the original that a change cancels).
     * Returns only conflicts that involve a planned lesson.
     */
    List<ConflictDto> preview(Collection<PlannedLesson> planned, Collection<UUID> ignoredLessonIds);
}
