package ua.vtc.rozklad.core.load.internal;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.contracts.events.TimetableVersionPublished;
import ua.vtc.rozklad.core.calendar.CalendarEdited;
import ua.vtc.rozklad.core.calendar.CalendarQueries;
import ua.vtc.rozklad.core.calendar.StudyCalendar;
import ua.vtc.rozklad.core.calendar.StudySemester;
import ua.vtc.rozklad.core.load.LoadItemRepository;

/**
 * Keeps AUTO plans current (owner's rule 15.11.2026). Both listeners run inside the transaction of the change, so a
 * refused or dry-run calendar edit rolls the plans back with it:
 * <ul>
 *   <li>a calendar edit (period, kind effect, parity override, semester dates, academic calendar import) recounts the
 *       plans of the groups it names in the semesters its dates fall in — one calendar read, whatever the dates;</li>
 *   <li>a published timetable version recounts the semester with that version's lessons.</li>
 * </ul>
 * At start-up every semester with load is recounted once, so plans stored by an older rule are brought in line.
 */
@Component
class LoadPlanEvents {

    private final LoadPlanner planner;
    private final CalendarQueries calendar;
    private final LoadItemRepository items;
    private final TransactionTemplate transactions;

    LoadPlanEvents(LoadPlanner planner, CalendarQueries calendar, LoadItemRepository items,
                   TransactionTemplate transactions) {
        this.planner = planner;
        this.calendar = calendar;
        this.items = items;
        this.transactions = transactions;
    }

    @EventListener
    void on(CalendarEdited event) {
        if (event.dates().isEmpty() || event.groupIds().isEmpty()) {
            return;
        }
        LocalDate from = event.dates().stream().min(Comparator.naturalOrder()).orElseThrow();
        LocalDate to = event.dates().stream().max(Comparator.naturalOrder()).orElseThrow();
        StudyCalendar after = calendar.studyCalendar(from, to);
        for (StudySemester semester : after.semesters()) {
            planner.recount(semester.semesterId(), event.groupIds(), null, after);
        }
    }

    @EventListener
    void on(TimetableVersionPublished event) {
        planner.recount(event.semesterId(), null, event.versionId(), null);
    }

    @EventListener(ApplicationReadyEvent.class)
    void recountOnStart() {
        List<UUID> semesters = transactions.execute(status -> items.findSemesterIds());
        if (semesters == null) {
            return;
        }
        for (UUID semesterId : semesters) {
            transactions.executeWithoutResult(status -> planner.recount(semesterId, null, null, null));
        }
    }
}
