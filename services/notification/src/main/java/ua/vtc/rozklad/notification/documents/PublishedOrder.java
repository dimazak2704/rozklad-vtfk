package ua.vtc.rozklad.notification.documents;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.events.OrderPayload;

/** A published order as received in {@code order.published}; the source of public documents. */
@Entity
@Table(name = "published_order")
public class PublishedOrder implements Persistable<UUID> {

    @Id
    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "number")
    private Integer number;

    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private OrderKind kind;

    @Column(name = "parent_id")
    private UUID parentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private OrderPayload payload;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Transient
    private boolean persisted;

    protected PublishedOrder() {
    }

    PublishedOrder(OrderPayload payload, String payloadHash, UUID eventId, Instant receivedAt) {
        this.orderId = payload.id();
        replace(payload, payloadHash, eventId, receivedAt);
    }

    /** core re-sent the order (e.g. corrected): the newest payload wins. */
    void replace(OrderPayload payload, String payloadHash, UUID eventId, Instant receivedAt) {
        this.number = payload.number();
        this.orderDate = payload.date();
        this.kind = payload.kind();
        this.parentId = payload.parentId();
        this.payload = payload;
        this.payloadHash = payloadHash;
        this.eventId = eventId;
        this.receivedAt = receivedAt;
    }

    @Override
    public UUID getId() {
        return orderId;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.persisted = true;
    }

    public Integer getNumber() {
        return number;
    }

    public OrderPayload getPayload() {
        return payload;
    }

    public String getPayloadHash() {
        return payloadHash;
    }

    public UUID getParentId() {
        return parentId;
    }
}
