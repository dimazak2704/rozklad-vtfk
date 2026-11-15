package ua.vtc.rozklad.notification.email;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.notification.config.MailChannelProperties;
import ua.vtc.rozklad.notification.config.StandClock;
import ua.vtc.rozklad.notification.email.EmailRetryPolicy.Decision;

/**
 * The e-mail worker: every {@code app.mail.poll-interval} it expands due intents ({@link EmailExpander}) and sends
 * due letters: stale and obsolete letters are FAILED with the reason, the rate guard may defer a letter, then
 * the transport sends it and the outcome is recorded. Runs on its own thread whenever the channel is on —
 * independent of the Telegram bot. Delivery is at-least-once: a crash between the SMTP call and the record resends
 * after the lease (longer than the SMTP timeouts) expires; several instances are safe ({@code SKIP LOCKED}).
 */
@Component
public class EmailWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EmailWorker.class);
    private static final long WARN_EVERY_MS = 60_000;

    private final EmailExpander expander;
    private final EmailMessageRepository messages;
    private final MailTransport transport;
    private final EmailRateGuard guard;
    private final EmailRetryPolicy retryPolicy;
    private final MailChannel channel;
    private final MailChannelProperties properties;
    private final MailLinks links;
    private final TransactionTemplate transactions;
    private final ObjectProvider<EmailRelevance> relevance;
    private final Clock domainClock;
    private final Clock clock;
    private final MeterRegistry meters;
    private final AtomicLong lastConfigWarning = new AtomicLong();
    private ScheduledExecutorService executor;

    public EmailWorker(EmailExpander expander, EmailMessageRepository messages, MailTransport transport,
                       EmailRateGuard guard, EmailRetryPolicy retryPolicy, MailChannel channel,
                       MailChannelProperties properties, MailLinks links, TransactionTemplate transactions,
                       ObjectProvider<EmailRelevance> relevance, Clock clock, MeterRegistry meters) {
        this.expander = expander;
        this.messages = messages;
        this.transport = transport;
        this.guard = guard;
        this.retryPolicy = retryPolicy;
        this.channel = channel;
        this.properties = properties;
        this.links = links;
        this.transactions = transactions;
        this.relevance = relevance;
        this.domainClock = clock; // lessons end in the domain's (stand) time
        this.clock = StandClock.real(clock); // queue times are real
        this.meters = meters;
        Gauge.builder("notification.emails.pending", messages,
                        repository -> repository.countByStatusIn(List.of(EmailMessageStatus.QUEUED,
                                EmailMessageStatus.RETRYING)))
                .description("Letters waiting to be sent")
                .register(meters);
    }

    /** One poll: expands intents, then sends due letters. @return intents and letters claimed */
    public int processBatch() {
        int expanded = expander.expandDue();
        Instant now = clock.instant();
        List<EmailMessage> batch = transactions.execute(tx -> {
            List<EmailMessage> due = messages.lockDue(now, properties.batchSize());
            due.forEach(message -> message.lease(now.plus(properties.lease()), now));
            return due;
        });
        if (batch != null) {
            batch.forEach(this::process);
        }
        return expanded + (batch == null ? 0 : batch.size());
    }

    /** Processes everything that is due now (tests and manual runs). */
    public void drain() {
        for (int round = 0; round < 1000 && processBatch() > 0; round++) {
            // кожен раунд бере наступну партію
        }
    }

    void process(EmailMessage message) {
        UUID id = message.getId();
        Instant now = clock.instant();
        if (message.getCreatedAt().isBefore(now.minus(properties.maxAge()))) {
            fail(id, "Застаріле: не надіслано протягом " + properties.maxAge().toHours() + " год.", false);
            count("stale");
            return;
        }
        Optional<String> obsolete = obsoleteReason(message);
        if (obsolete.isPresent()) {
            fail(id, obsolete.get(), false);
            log.debug("E-mail {} ({}) not sent: {}", id, message.getKind(), obsolete.get());
            count("obsolete");
            return;
        }
        Duration wait = guard.tryAcquire(message.getToAddress());
        if (!wait.isZero()) {
            update(id, letter -> letter.defer(now.plus(wait), now));
            count("deferred");
            return;
        }
        if (!guard.withinDailyCap(message.getToAddress())) {
            log.warn("E-mail {} ({}) to {} refused: the daily cap of {} letters per address was reached", id,
                    message.getKind(), MailAddresses.mask(message.getToAddress()), properties.recipientPerDay());
            fail(id, "Перевищено денний ліміт листів на цю адресу.", true);
            count("failed");
            return;
        }
        try {
            String messageId = transport.send(outgoing(message));
            update(id, letter -> letter.markSent(messageId, clock.instant()));
            channel.recordSuccess();
            count("sent");
        } catch (MailTransportException e) {
            onFailure(message, e);
        } catch (RuntimeException e) {
            log.warn("E-mail {} to {} failed on our side: {}", id, MailAddresses.mask(message.getToAddress()),
                    e.toString());
            apply(message, retryPolicy.onTransientFailure(message.getAttempts() + 1, clock.instant()),
                    "Помилка підготовки листа: " + MailAddresses.maskAll(e.getMessage()));
        }
    }

    private OutgoingMail outgoing(EmailMessage message) {
        String thread = message.getCorrelationId() != null && message.getKind().followsChange()
                ? "change-" + message.getCorrelationId() + "@" + channel.messageIdDomain() : null;
        EmailAudience audience = message.getKind().audience() == EmailAudience.ADMIN
                ? EmailAudience.ADMIN : EmailAudience.TEACHER;
        return new OutgoingMail(message.getToAddress(), message.getAccountName(), message.getSubject(),
                message.getBodyHtml(), message.getBodyText(), message.getId() + "@" + channel.messageIdDomain(),
                thread, message.getKind().listed() ? links.settings(audience) : null);
    }

    /** Too late for the lesson, or the module behind the change says the letter is not needed any more. */
    private Optional<String> obsoleteReason(EmailMessage message) {
        if (message.getKind().followsChange() && lessonOver(message.getLessonDate(), message.getLessonEndsAt())) {
            return Optional.of("Неактуальне: пара вже минула.");
        }
        return relevance.orderedStream()
                .map(check -> check.obsoleteReason(message))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private boolean lessonOver(LocalDate date, LocalTime endsAt) {
        if (date == null) {
            return false;
        }
        LocalDate today = LocalDate.now(domainClock);
        return date.isBefore(today)
                || date.equals(today) && endsAt != null && !endsAt.isAfter(LocalTime.now(domainClock));
    }

    private void onFailure(EmailMessage message, MailTransportException failure) {
        if (failure.configProblem()) {
            long now = clock.millis();
            long last = lastConfigWarning.get();
            if (now - last > WARN_EVERY_MS && lastConfigWarning.compareAndSet(last, now)) {
                log.warn("E-mail channel misconfigured: {}", failure.getMessage());
            }
        } else {
            log.warn("E-mail {} ({}) to {} failed: {}", message.getId(), message.getKind(),
                    MailAddresses.mask(message.getToAddress()), failure.getMessage());
        }
        channel.recordFailure(failure.getMessage());
        apply(message, retryPolicy.onSendFailure(failure, message.getAttempts() + 1, clock.instant()),
                failure.getMessage());
    }

    private void apply(EmailMessage message, Decision decision, String error) {
        switch (decision) {
            case Decision.Retry retry -> {
                update(message.getId(), letter -> letter.markRetry(error, retry.at(), clock.instant()));
                count("retry");
            }
            case Decision.Fail ignored -> {
                fail(message.getId(), error, true);
                count("failed");
            }
        }
    }

    private void fail(UUID id, String error, boolean countsAsAttempt) {
        update(id, letter -> letter.markFailed(error, countsAsAttempt, clock.instant()));
    }

    private void update(UUID id, Consumer<EmailMessage> change) {
        transactions.executeWithoutResult(tx -> messages.findById(id).ifPresent(change));
    }

    private void count(String outcome) {
        meters.counter("notification.emails", "outcome", outcome).increment();
    }

    @Override
    public synchronized void start() {
        if (!properties.workerEnabled() || !channel.enabled() || executor != null) {
            if (!channel.enabled()) {
                log.info("E-mail worker not started: the e-mail channel is off");
            }
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "email-worker");
            thread.setDaemon(true);
            return thread;
        });
        long interval = properties.pollInterval().toMillis();
        executor.scheduleWithFixedDelay(this::tick, interval, interval, TimeUnit.MILLISECONDS);
        log.info("E-mail worker started (every {} ms, batch {})", interval, properties.batchSize());
    }

    private void tick() {
        try {
            processBatch();
        } catch (RuntimeException e) {
            log.error("E-mail worker poll failed", e);
        }
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
