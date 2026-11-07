package ua.vtc.rozklad.contracts.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** {@code order.published}: core → notification. JSON: envelope fields plus {@code order}. */
public record OrderPublished(
        UUID eventId,
        Instant occurredAt,
        int version,
        OrderPayload order) implements RozkladEvent {

    public OrderPublished {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(order, "order");
    }

    public OrderPublished(EventEnvelope envelope, OrderPayload order) {
        this(envelope.eventId(), envelope.occurredAt(), envelope.version(), order);
    }
}
