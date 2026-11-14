package ua.vtc.rozklad.notification.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.contracts.events.AbsenceReported;
import ua.vtc.rozklad.contracts.events.GenerationCancelRequested;
import ua.vtc.rozklad.contracts.events.GenerationCompleted;
import ua.vtc.rozklad.contracts.events.GenerationProgress;
import ua.vtc.rozklad.contracts.events.GenerationRequested;
import ua.vtc.rozklad.contracts.events.OrderPublished;
import ua.vtc.rozklad.contracts.events.RozkladEvent;
import ua.vtc.rozklad.contracts.events.ScheduleChanged;
import ua.vtc.rozklad.contracts.events.SubstitutionConfirmationRequested;
import ua.vtc.rozklad.contracts.events.SubstitutionDetailsChanged;
import ua.vtc.rozklad.contracts.events.SubstitutionStatusChanged;
import ua.vtc.rozklad.contracts.events.SubstitutionTeacherReplied;
import ua.vtc.rozklad.contracts.events.TimetableVersionPublished;
import ua.vtc.rozklad.notification.core.CoreQueries;
import ua.vtc.rozklad.notification.orders.OrderDistribution;
import ua.vtc.rozklad.notification.substitution.SubstitutionRequests;

/**
 * Handles one event exactly once: the event id is recorded in {@code processed_event} in the same
 * transaction that queues the resulting messages. Teacher names for admin texts and the public history window
 * of a past-dated order are looked up in core before that transaction, so no HTTP call runs while the handlers
 * hold a row lock.
 */
@Service
public class EventHandlers {

    private static final Logger log = LoggerFactory.getLogger(EventHandlers.class);

    private final ProcessedEvents processedEvents;
    private final OrderDistribution orders;
    private final SubstitutionRequests substitutions;
    private final AdminNotices adminNotices;
    private final CoreQueries queries;
    private final TransactionTemplate transactions;

    public EventHandlers(ProcessedEvents processedEvents, OrderDistribution orders,
                         SubstitutionRequests substitutions, AdminNotices adminNotices, CoreQueries queries,
                         TransactionTemplate transactions) {
        this.processedEvents = processedEvents;
        this.orders = orders;
        this.substitutions = substitutions;
        this.adminNotices = adminNotices;
        this.queries = queries;
        this.transactions = transactions;
    }

    public void handle(RozkladEvent event) {
        String teacherName = substitutions.adminNameFor(event);
        boolean orderBeforePublicWindow = orders.beforePublicWindow(event);
        transactions.executeWithoutResult(tx -> handle(event, teacherName, orderBeforePublicWindow));
    }

    private void handle(RozkladEvent event, String teacherName, boolean orderBeforePublicWindow) {
        if (!processedEvents.markProcessed(event.eventId())) {
            log.debug("Event {} ({}) was already processed", event.eventId(), event.getClass().getSimpleName());
            return;
        }
        switch (event) {
            case OrderPublished published -> orders.onPublished(published, orderBeforePublicWindow);
            case SubstitutionConfirmationRequested requested -> substitutions.onConfirmationRequested(requested);
            case SubstitutionStatusChanged changed -> substitutions.onStatusChanged(changed, teacherName);
            case SubstitutionTeacherReplied replied -> substitutions.onTeacherReplied(replied, teacherName);
            case SubstitutionDetailsChanged changed -> substitutions.onDetailsChanged(changed);
            case AbsenceReported absence -> adminNotices.onAbsenceReported(absence);
            case GenerationCompleted completed -> adminNotices.onGenerationCompleted(completed);
            case ScheduleChanged ignored -> queries.invalidateSchedules();
            case TimetableVersionPublished ignored -> queries.invalidateSchedules();
            case GenerationRequested ignored -> log.debug("generation.requested is not for notification");
            case GenerationCancelRequested ignored -> log.debug("generation.cancel is not for notification");
            case GenerationProgress ignored -> log.debug("generation.progress is not for notification");
        }
    }
}
