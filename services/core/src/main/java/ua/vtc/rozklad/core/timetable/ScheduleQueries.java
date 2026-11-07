package ua.vtc.rozklad.core.timetable;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Read API of dated schedules for other modules (e.g. {@code GET /api/me/schedule}, absences,
 * substitution options). One database query per call for the lessons, no N+1.
 */
public interface ScheduleQueries {

    /** Longest date range of one schedule request, in days (CONTRACTS.md §1). */
    int MAX_DAYS = 31;

    /**
     * Schedule of a group, teacher or room for {@code [from, to]} (both inclusive, at most
     * {@link #MAX_DAYS} days).
     *
     * @throws ua.vtc.rozklad.core.shared.error.NotFoundException when the owner does not exist
     * @throws IllegalArgumentException when the range is empty or longer than {@link #MAX_DAYS}
     */
    ScheduleView schedule(ScheduleOwnerType type, UUID ownerId, LocalDate from, LocalDate to);

    /** Views of the given dated lessons (unknown ids are skipped), keyed by lesson id. */
    Map<UUID, LessonView> lessonViews(Collection<UUID> lessonIds);
}
