package ua.vtc.rozklad.notification.delivery;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.notification.binding.ChatBindings;
import ua.vtc.rozklad.notification.config.DeliveryProperties;
import ua.vtc.rozklad.notification.config.TelegramProperties;
import ua.vtc.rozklad.notification.delivery.MediaResolver.MediaUnavailableException;
import ua.vtc.rozklad.notification.delivery.RetryPolicy.Decision;
import ua.vtc.rozklad.notification.shared.ChatIds;
import ua.vtc.rozklad.notification.telegram.TelegramApiFailure;

/**
 * Sends queued messages: claims due messages (the oldest pending one per chat), checks the rate
 * limits, calls Telegram and records the outcome. Runs on its own thread every
 * {@code app.delivery.poll-interval} while the bot is enabled. Delivery is at-least-once: a crash
 * between the Telegram call and the status update resends after the lease expires.
 */
@Component
public class DeliveryWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
    private static final Duration MAX_SYNC_WAIT = Duration.ofSeconds(3);

    private final DeliveryRecorder recorder;
    private final MessageSender sender;
    private final RateLimiter limiter;
    private final RetryPolicy retryPolicy;
    private final ChatBindings bindings;
    private final DeliveryProperties properties;
    private final TelegramProperties telegram;
    private final Clock clock;
    private final MeterRegistry meters;
    private ScheduledExecutorService executor;

    public DeliveryWorker(DeliveryRecorder recorder, MessageSender sender, RateLimiter limiter,
                          RetryPolicy retryPolicy, ChatBindings bindings, DeliveryProperties properties,
                          TelegramProperties telegram, Clock clock, MeterRegistry meters) {
        this.recorder = recorder;
        this.sender = sender;
        this.limiter = limiter;
        this.retryPolicy = retryPolicy;
        this.bindings = bindings;
        this.properties = properties;
        this.telegram = telegram;
        this.clock = clock;
        this.meters = meters;
    }

    /** One poll: claims a batch and processes it. @return number of claimed messages */
    public int processBatch() {
        List<OutboundMessage> batch = recorder.claim(clock.instant(), properties.batchSize(), properties.lease());
        for (OutboundMessage message : batch) {
            process(message, false);
        }
        return batch.size();
    }

    /** Processes everything that is due now (tests and manual runs). */
    public void drain() {
        for (int round = 0; round < 1000 && processBatch() > 0; round++) {
            // кожен раунд бере наступне повідомлення кожного чату
        }
    }

    /** Sends one message right away, waiting briefly for the rate limiter (admin test message). */
    public OutboundMessage deliverNow(UUID id) {
        Optional<OutboundMessage> claimed = recorder.claimOne(id, clock.instant(), properties.lease());
        claimed.ifPresent(message -> process(message, true));
        return recorder.get(id);
    }

    void process(OutboundMessage message, boolean waitForRateLimit) {
        UUID id = message.getId();
        Instant now = clock.instant();
        if (message.getCreatedAt().isBefore(now.minus(properties.maxAge()))) {
            recorder.markFailed(id, "Застаріле: не доставлено протягом " + properties.maxAge().toHours()
                    + " год.", false, now);
            count("stale");
            return;
        }
        if (!bindings.isKnownChat(message.getChatId())) {
            recorder.markFailed(id, "Чат більше не прив’язаний до бота.", false, now);
            count("unbound");
            return;
        }
        Duration wait = acquire(message.getChatId(), waitForRateLimit);
        if (!wait.isZero()) {
            recorder.defer(id, now.plus(wait), now);
            count("deferred");
            return;
        }
        try {
            Long telegramMessageId = sender.send(message);
            recorder.markSent(id, telegramMessageId, clock.instant());
            count("sent");
        } catch (TelegramApiFailure failure) {
            onTelegramFailure(message, failure);
        } catch (MediaUnavailableException e) {
            recorder.markFailed(id, e.getMessage(), true, clock.instant());
            count("failed");
        } catch (RuntimeException e) {
            log.warn("Delivery {} to chat {} failed on our side: {}", id, ChatIds.mask(message.getChatId()),
                    e.toString());
            apply(message, retryPolicy.onTransientFailure(message.getAttempts() + 1, clock.instant()),
                    "Помилка підготовки повідомлення: " + e.getMessage());
        }
    }

    /** @return zero when a token was taken, otherwise how long the message must wait */
    private Duration acquire(long chatId, boolean waitBriefly) {
        Duration delay = limiter.tryAcquire(chatId);
        if (!delay.isZero() && waitBriefly && delay.compareTo(MAX_SYNC_WAIT) <= 0) {
            sleep(delay);
            delay = limiter.tryAcquire(chatId);
        }
        return delay;
    }

    private void onTelegramFailure(OutboundMessage message, TelegramApiFailure failure) {
        String error = failure.errorCode() == null
                ? "Мережа: " + failure.getMessage()
                : "Telegram " + failure.errorCode() + ": " + failure.getMessage();
        Decision decision = retryPolicy.onTelegramFailure(failure, message.getAttempts() + 1, clock.instant());
        if (decision instanceof Decision.Fail && failure.isForbidden()) {
            log.warn("Telegram refused chat {} ({}): the bot was blocked or removed", ChatIds.mask(message.getChatId()),
                    message.getPurpose());
        }
        apply(message, decision, error);
    }

    private void apply(OutboundMessage message, Decision decision, String error) {
        Instant now = clock.instant();
        switch (decision) {
            case Decision.Retry retry -> {
                recorder.markRetry(message.getId(), error, retry.at(), retry.countsAsAttempt(), now);
                count("retry");
            }
            case Decision.Fail ignored -> {
                recorder.markFailed(message.getId(), error, true, now);
                count("failed");
            }
            case Decision.Migrate migrate -> {
                bindings.migrateChat(message.getChatId(), migrate.newChatId());
                recorder.retarget(message.getId(), migrate.newChatId(), now);
                count("migrated");
            }
        }
    }

    private void count(String outcome) {
        meters.counter("notification.deliveries", "outcome", outcome).increment();
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void start() {
        if (!properties.workerEnabled() || !telegram.enabled() || executor != null) {
            if (!telegram.enabled()) {
                log.info("Delivery worker not started: Telegram bot is disabled (messages stay queued)");
            }
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "delivery-worker");
            thread.setDaemon(true);
            return thread;
        });
        long interval = properties.pollInterval().toMillis();
        executor.scheduleWithFixedDelay(this::tick, interval, interval, TimeUnit.MILLISECONDS);
        log.info("Delivery worker started (every {} ms, batch {})", interval, properties.batchSize());
    }

    private void tick() {
        try {
            processBatch();
        } catch (RuntimeException e) {
            log.error("Delivery worker poll failed", e);
        }
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
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
