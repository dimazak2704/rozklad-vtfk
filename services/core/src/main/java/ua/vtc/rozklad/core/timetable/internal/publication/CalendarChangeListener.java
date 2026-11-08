package ua.vtc.rozklad.core.timetable.internal.publication;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.events.ScheduleChangeReason;
import ua.vtc.rozklad.contracts.events.ScheduleChanged;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.timetable.DatedLessonRefresh;

/**
 * Keeps dated lessons in line with calendar edits (DOMAIN §6): the calendar module announces the
 * dates and groups of a period, parity override or semester edit inside its transaction, and their
 * lessons are re-materialized from today on before it commits. Lessons of live changes are not
 * touched, so an edit that would drop a template lesson because a changed lesson holds its teacher
 * or room, or leave a changed lesson on a day without classes, is refused with 409 and
 * {@code conflicts[]}: the whole edit rolls back and the dispatcher decides first (cancel the
 * decision, publish an amendment) instead of losing a lesson without notice.
 */
@Component
class CalendarChangeListener {

    static final String REFUSED = "Календар не змінено: це зачепило б заняття, змінені розпорядженнями, або "
            + "відкриті рішення диспетчера. Спершу розв'яжіть конфлікти (скасуйте рішення або пару доповненням), "
            + "потім повторіть.";

    private static final Logger log = LoggerFactory.getLogger(CalendarChangeListener.class);

    private final LessonRefresher refresher;

    CalendarChangeListener(LessonRefresher refresher) {
        this.refresher = refresher;
    }

    @EventListener
    void on(ScheduleChanged event) {
        if (event.reason() != ScheduleChangeReason.CALENDAR_CHANGED) {
            return;
        }
        DatedLessonRefresh.Result result = refresher.reconcile(event.dates(), event.groupIds());
        List<ConflictDto> conflicts = new ArrayList<>(result.clashes());
        conflicts.addAll(refresher.strandedByCalendar(event.dates(), event.groupIds()));
        if (!conflicts.isEmpty()) {
            log.info("Calendar edit refused: {} conflicts with lessons of live changes", conflicts.size());
            throw new ConflictException(REFUSED, conflicts);
        }
        if (result.changedAnything()) {
            log.info("Calendar edit re-materialized lessons: {} added, {} removed, {} updated",
                    result.added(), result.removed(), result.updated());
        }
    }
}
