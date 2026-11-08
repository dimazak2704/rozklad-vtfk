package ua.vtc.rozklad.notification.maintenance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.notification.binding.BindingCodes;
import ua.vtc.rozklad.notification.config.DeliveryProperties;
import ua.vtc.rozklad.notification.config.TelegramProperties;
import ua.vtc.rozklad.notification.delivery.OutboundMessageRepository;
import ua.vtc.rozklad.notification.delivery.OutboundStatus;
import ua.vtc.rozklad.notification.events.ProcessedEvents;
import ua.vtc.rozklad.notification.substitution.SubstitutionRequests;

/**
 * Periodic jobs: confirmation deadlines and warnings about teachers without Telegram (every minute),
 * nightly cleanup of old rows.
 */
@Component
public class ScheduledJobs {

    static final Duration PROCESSED_EVENTS_RETENTION = Duration.ofDays(30);
    static final Duration EXPIRED_CODES_RETENTION = Duration.ofDays(7);
    static final int SUBSTITUTIONS_RETENTION_DAYS = 60;

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);

    private final SubstitutionRequests substitutions;
    private final OutboundMessageRepository outbound;
    private final ProcessedEvents processedEvents;
    private final BindingCodes codes;
    private final DeliveryProperties delivery;
    private final TelegramProperties telegram;
    private final Clock clock;

    public ScheduledJobs(SubstitutionRequests substitutions, OutboundMessageRepository outbound,
                         ProcessedEvents processedEvents, BindingCodes codes, DeliveryProperties delivery,
                         TelegramProperties telegram, Clock clock) {
        this.substitutions = substitutions;
        this.outbound = outbound;
        this.processedEvents = processedEvents;
        this.codes = codes;
        this.delivery = delivery;
        this.telegram = telegram;
        this.clock = clock;
    }

    /**
     * Confirmation deadlines, then warnings about teachers without Telegram. One method on purpose: the
     * scheduler runs on virtual threads, so two methods with the same delay would start together and lock
     * overlapping {@code substitution_request} rows side by side. A failure of the first job does not
     * skip the second one; both are tried again in a minute.
     */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void substitutionDeadlines() {
        if (!telegram.enabled()) {
            return;
        }
        runSafely("confirmation deadlines", substitutions::remindOverdue);
        runSafely("teachers without Telegram", substitutions::reportTeachersWithoutTelegram);
    }

    private static void runSafely(String job, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("Scheduled job «{}» failed; it runs again in a minute", job, e);
        }
    }

    @Scheduled(cron = "0 40 3 * * *", zone = "Europe/Kyiv")
    @Transactional
    public void housekeeping() {
        Instant now = clock.instant();
        int deliveries = outbound.deleteFinishedBefore(List.of(OutboundStatus.SENT, OutboundStatus.FAILED),
                now.minus(delivery.retention()));
        int events = processedEvents.purgeBefore(now.minus(PROCESSED_EVENTS_RETENTION));
        int expiredCodes = codes.purgeExpired(now.minus(EXPIRED_CODES_RETENTION));
        int requests = substitutions.purgeLessonsBefore(LocalDate.now(clock).minusDays(SUBSTITUTIONS_RETENTION_DAYS));
        log.info("Housekeeping: removed {} deliveries, {} processed events, {} expired codes, {} old substitution "
                + "requests", deliveries, events, expiredCodes, requests);
    }
}
