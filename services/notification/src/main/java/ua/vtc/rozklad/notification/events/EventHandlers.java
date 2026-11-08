package ua.vtc.rozklad.notification.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.events.AbsenceReported;
import ua.vtc.rozklad.contracts.events.GenerationCancelRequested;
import ua.vtc.rozklad.contracts.events.GenerationCompleted;
import ua.vtc.rozklad.contracts.events.GenerationProgress;
import ua.vtc.rozklad.contracts.events.GenerationRequested;
import ua.vtc.rozklad.contracts.events.OrderPublished;
import ua.vtc.rozklad.contracts.events.RozkladEvent;
import ua.vtc.rozklad.contracts.events.ScheduleChanged;
import ua.vtc.rozklad.contracts.events.SubstitutionConfirmationRequested;
import ua.vtc.rozklad.contracts.events.SubstitutionStatusChanged;
import ua.vtc.rozklad.contracts.events.SubstitutionTeacherReplied;
import ua.vtc.rozklad.contracts.events.TimetableVersionPublished;
import ua.vtc.rozklad.notification.core.CoreQueries;
import ua.vtc.rozklad.notification.orders.OrderDistribution;
import ua.vtc.rozklad.notification.substitution.SubstitutionRequests;

/**
 * Handles one event exactly once: the event id is recorded in {@code processed_event} in the same
 * transaction that queues the resulting messages.
 */
@Service
public class EventHandlers {

    private static final Logger log = LoggerFactory.getLogger(EventHandlers.class);

    private final ProcessedEvents processedEvents;
    private final OrderDistribution orders;
    private final SubstitutionRequests substitutions;
    private final AdminNotices adminNotices;
    private final CoreQueries queries;

    public EventHandlers(ProcessedEvents processedEvents, OrderDistribution orders,
                         SubstitutionRequests substitutions, AdminNotices adminNotices, CoreQueries queries) {
        this.processedEvents = processedEvents;
        this.orders = orders;
        this.substitutions = substitutions;
        this.adminNotices = adminNotices;
        this.queries = queries;
    }

    @Transactional
    public void handle(RozkladEvent event) {
        if (!processedEvents.markProcessed(event.eventId())) {
            log.debug("Event {} ({}) was already processed", event.eventId(), event.getClass().getSimpleName());
            return;
        }
        switch (event) {
            case OrderPublished published -> orders.onPublished(published);
            case SubstitutionConfirmationRequested requested -> substitutions.onConfirmationRequested(requested);
            case SubstitutionStatusChanged changed -> substitutions.onStatusChanged(changed);
            case SubstitutionTeacherReplied replied -> substitutions.onTeacherReplied(replied);
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
