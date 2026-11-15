package ua.vtc.rozklad.core.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * In-process event of a calendar edit (period, parity override, semester dates, effect of a period kind,
 * import of the academic calendar), published inside the edit's transaction after the edit is flushed and
 * before {@code schedule.changed}. A listener may refuse the edit by throwing: the whole transaction rolls back.
 *
 * @param dates    Mon–Fri dates whose lessons may change
 * @param groupIds groups whose lessons may change
 * @param before   the calendar of those dates as it was before the edit, so a listener can tell what
 *                 the edit itself changes from what was already so
 * @param outcome  what the listeners did with the dated lessons, for a dry run that reports it
 */
public record CalendarEdited(List<LocalDate> dates, List<UUID> groupIds, StudyCalendar before, Outcome outcome) {

    public CalendarEdited {
        dates = List.copyOf(dates);
        groupIds = List.copyOf(groupIds);
        outcome = outcome == null ? new Outcome() : outcome;
    }

    public CalendarEdited(List<LocalDate> dates, List<UUID> groupIds, StudyCalendar before) {
        this(dates, groupIds, before, new Outcome());
    }

    /** Lessons the listeners added, removed and updated for this edit (0 until a listener reports). */
    public static final class Outcome {

        private int added;
        private int removed;
        private int updated;

        public void lessonsChanged(int added, int removed, int updated) {
            this.added += added;
            this.removed += removed;
            this.updated += updated;
        }

        public int added() {
            return added;
        }

        public int removed() {
            return removed;
        }

        public int updated() {
            return updated;
        }
    }
}
