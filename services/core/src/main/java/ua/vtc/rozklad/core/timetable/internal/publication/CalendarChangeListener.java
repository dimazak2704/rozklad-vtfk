package ua.vtc.rozklad.core.timetable.internal.publication;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.contracts.events.ScheduleChangeReason;
import ua.vtc.rozklad.contracts.events.ScheduleChanged;
import ua.vtc.rozklad.core.timetable.DatedLessonRefresh;

/**
 * Dated lessons follow the calendar (DOMAIN §2, §6): a holiday, practice, study-mode period,
 * parity override or new semester dates edited after publication re-materialize the dates and
 * groups that the calendar module announces with {@code schedule.changed} (CALENDAR_CHANGED). The
 * listener runs synchronously inside the transaction of the calendar edit, so the edit and its
 * lessons commit or roll back together.
 */
@Component
class CalendarChangeListener {

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
        if (result.changedAnything()) {
            log.info("Calendar edit re-materialized lessons: {} added, {} removed, {} updated",
                    result.added(), result.removed(), result.updated());
        }
        if (!result.clashes().isEmpty()) {
            log.warn("Calendar edit left {} template lessons uncreated: a lesson changed by an order holds the "
                    + "teacher or the room", result.clashes().size());
        }
    }
}
