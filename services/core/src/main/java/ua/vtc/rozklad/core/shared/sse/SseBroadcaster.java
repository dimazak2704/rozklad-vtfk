package ua.vtc.rozklad.core.shared.sse;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import ua.vtc.rozklad.core.shared.error.ProblemTypes;
import ua.vtc.rozklad.core.shared.security.CurrentUser;
import ua.vtc.rozklad.core.shared.time.StandClock;

/**
 * Keeps open SSE connections and pushes realtime events to a user, a role or everybody. Sends a
 * {@value SseEventTypes#PING} every 25 s so proxies keep the connection and dead clients are
 * dropped. Only for ADMIN/TEACHER sessions; never put private data (absence reasons) into events.
 * <p>
 * A stream lives no longer than the access token it was opened with (at most 30 min): the roles are read
 * once, at subscribe, so a revoked account or a removed role stops receiving events when the token expires,
 * and the client reconnects with a fresh token. Every stream holds a connection through the proxies, so one
 * account may keep {@value #MAX_STREAMS_PER_USER} streams (429 above that) and all together
 * {@value #MAX_STREAMS} (503): one abused staff session cannot take the connections the public site needs.
 */
@Component
public class SseBroadcaster {

    static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);
    /** A token the decoder still accepts within its clock skew gets a stream that closes at once. */
    static final Duration MIN_EMITTER_TIMEOUT = Duration.ofSeconds(1);
    static final long HEARTBEAT_MILLIS = 25_000;
    /** Open tabs of one account; a user with more tabs gets live updates in the first ten. */
    static final int MAX_STREAMS_PER_USER = 10;
    static final int MAX_STREAMS = 1000;
    private static final long RECONNECT_MILLIS = 5_000;
    private static final String RETRY_AFTER_SECONDS = "30";

    private static final Logger log = LoggerFactory.getLogger(SseBroadcaster.class);

    private final Map<UUID, Subscriber> subscribers = new ConcurrentHashMap<>();
    /** Serializes the limit check with the registration; sending and removal do not take it. */
    private final Object registration = new Object();
    private final Clock clock;
    private final Clock realClock;

    public SseBroadcaster(Clock clock) {
        this.clock = clock;
        this.realClock = StandClock.real(clock); // токен спливає в реальному часі, не в часі стенда
    }

    /**
     * Registers a connection of the user until {@code tokenExpiresAt} (at most 30 min; without an expiry
     * 30 min); the client reconnects after the timeout automatically.
     *
     * @throws ErrorResponseException 429 when the account already has {@value #MAX_STREAMS_PER_USER} streams,
     *                                503 when the server holds {@value #MAX_STREAMS}
     */
    public SseEmitter subscribe(CurrentUser user, Instant tokenExpiresAt) {
        SseEmitter emitter = new SseEmitter(timeout(tokenExpiresAt).toMillis());
        Subscriber subscriber = new Subscriber(UUID.randomUUID(), user.id(), user.roles(), emitter);
        synchronized (registration) {
            if (subscribers.size() >= MAX_STREAMS) {
                log.warn("SSE refused for {}: {} streams are open", user.username(), subscribers.size());
                throw refused(HttpStatus.SERVICE_UNAVAILABLE, ProblemTypes.SERVICE_UNAVAILABLE,
                        "Живі оновлення тимчасово недоступні",
                        "Сервер зараз тримає забагато з'єднань живих оновлень. Сторінка підключиться сама.");
            }
            long own = subscribers.values().stream()
                    .filter(other -> Objects.equals(other.userId(), user.id()))
                    .count();
            if (own >= MAX_STREAMS_PER_USER) {
                log.info("SSE refused for {}: {} streams of the account are open", user.username(), own);
                throw refused(HttpStatus.TOO_MANY_REQUESTS, ProblemTypes.TOO_MANY_REQUESTS, "Забагато вкладок",
                        ("Живі оновлення працюють щонайбільше в %d вкладках одночасно. "
                                + "Закрийте зайві вкладки, і ця підключиться сама.").formatted(MAX_STREAMS_PER_USER));
            }
            subscribers.put(subscriber.id(), subscriber);
        }
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

    /** Until the token expires, measured in real time (the stand time may be shifted by days). */
    private Duration timeout(Instant tokenExpiresAt) {
        if (tokenExpiresAt == null) {
            return EMITTER_TIMEOUT;
        }
        Duration left = Duration.between(realClock.instant(), tokenExpiresAt);
        if (left.compareTo(MIN_EMITTER_TIMEOUT) < 0) {
            return MIN_EMITTER_TIMEOUT;
        }
        return left.compareTo(EMITTER_TIMEOUT) < 0 ? left : EMITTER_TIMEOUT;
    }

    private static ErrorResponseException refused(HttpStatus status, URI type, String title, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(type);
        body.setTitle(title);
        ErrorResponseException refused = new ErrorResponseException(status, body, null);
        refused.getHeaders().set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        return refused;
    }

    private Map<String, String> pingPayload() {
        return Map.of("at", clock.instant().toString());
    }

    record Subscriber(UUID id, UUID userId, Set<String> roles, SseEmitter emitter) {
    }
}
