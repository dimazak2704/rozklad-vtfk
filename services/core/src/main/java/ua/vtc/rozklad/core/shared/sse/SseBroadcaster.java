package ua.vtc.rozklad.core.shared.sse;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ua.vtc.rozklad.core.shared.security.CurrentUser;

/**
 * Keeps open SSE connections and pushes realtime events to a user, a role or everybody. Sends a
 * {@value SseEventTypes#PING} every 25 s so proxies keep the connection and dead clients are
 * dropped. Only for ADMIN/TEACHER sessions; never put private data (absence reasons) into events.
 */
@Component
public class SseBroadcaster {

    static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);
    static final long HEARTBEAT_MILLIS = 25_000;
    private static final long RECONNECT_MILLIS = 5_000;

    private static final Logger log = LoggerFactory.getLogger(SseBroadcaster.class);

    private final Map<UUID, Subscriber> subscribers = new ConcurrentHashMap<>();
    private final Clock clock;

    public SseBroadcaster(Clock clock) {
        this.clock = clock;
    }

    /** Registers a connection of the user; the client reconnects after timeout automatically. */
    public SseEmitter subscribe(CurrentUser user) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());
        Subscriber subscriber = new Subscriber(UUID.randomUUID(), user.id(), user.roles(), emitter);
        subscribers.put(subscriber.id(), subscriber);
        emitter.onCompletion(() -> subscribers.remove(subscriber.id()));
        emitter.onTimeout(() -> {
            subscribers.remove(subscriber.id());
            emitter.complete();
        });
        emitter.onError(error -> subscribers.remove(subscriber.id()));
        send(subscriber, SseEmitter.event().name(SseEventTypes.PING).reconnectTime(RECONNECT_MILLIS)
                .data(pingPayload(), MediaType.APPLICATION_JSON));
        return emitter;
    }

    public void sendToUser(UUID userId, String eventType, Object data) {
        publish(subscriber -> userId.equals(subscriber.userId()), eventType, data);
    }

    public void sendToRole(String role, String eventType, Object data) {
        publish(subscriber -> subscriber.roles().contains(role), eventType, data);
    }

    public void broadcast(String eventType, Object data) {
        publish(subscriber -> true, eventType, data);
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    @Scheduled(fixedRate = HEARTBEAT_MILLIS, initialDelay = HEARTBEAT_MILLIS)
    void heartbeat() {
        broadcast(SseEventTypes.PING, pingPayload());
    }

    private void publish(Predicate<Subscriber> audience, String eventType, Object data) {
        subscribers.values().stream()
                .filter(audience)
                .forEach(subscriber -> send(subscriber,
                        SseEmitter.event().name(eventType).data(data, MediaType.APPLICATION_JSON)));
    }

    private void send(Subscriber subscriber, SseEmitter.SseEventBuilder event) {
        try {
            subscriber.emitter().send(event);
        } catch (IOException | IllegalStateException disconnected) {
            // клієнт закрив вкладку або з'єднання вже завершене: просто забуваємо підписника
            log.debug("Dropping SSE subscriber {}: {}", subscriber.id(), disconnected.getMessage());
            subscribers.remove(subscriber.id());
            try {
                subscriber.emitter().completeWithError(disconnected);
            } catch (IllegalStateException alreadyCompleted) {
                log.trace("SSE emitter {} was already completed", subscriber.id());
            }
        }
    }

    private Map<String, String> pingPayload() {
        return Map.of("at", clock.instant().toString());
    }

    record Subscriber(UUID id, UUID userId, Set<String> roles, SseEmitter emitter) {
    }
}
