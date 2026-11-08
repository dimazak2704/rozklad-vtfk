package ua.vtc.rozklad.core.timetable;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ua.vtc.rozklad.core.shared.error.ConflictDto;

/**
 * Brings the dated lessons of some groups on some dates back in line with the timetable version
 * that governs each date and with the calendar (DOMAIN §2, §6): a closed day has no template
 * lessons, a study-mode day marks them online, a parity override picks the other week's lessons,
 * and a lesson of an older version that only an abandoned decision kept is replaced by the
 * current one. Lessons created or referenced by a live change are never touched. Past dates are
 * history and are left as they are. Lessons that already match keep their ids. Runs in the
 * caller's transaction and announces {@code schedule.changed} (LESSONS_EDITED) when anything
 * changed.
 */
public interface DatedLessonRefresh {

    Result refresh(Collection<LocalDate> dates, Collection<UUID> groupIds);

    /**
     * @param removedLessonIds lessons deleted (links of changes to them are cleared by the database)
     * @param clashes          template lessons not created because a kept lesson holds the teacher or room
     */
    record Result(int added, int removed, int updated, Set<UUID> removedLessonIds, List<ConflictDto> clashes) {

        public static final Result NONE = new Result(0, 0, 0, Set.of(), List.of());

        public Result {
            removedLessonIds = Set.copyOf(removedLessonIds);
            clashes = List.copyOf(clashes);
        }

        public boolean changedAnything() {
            return added + removed + updated > 0;
        }
    }
}
