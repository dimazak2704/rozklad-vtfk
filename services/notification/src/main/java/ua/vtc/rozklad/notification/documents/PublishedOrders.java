package ua.vtc.rozklad.notification.documents;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.OrderStatus;
import ua.vtc.rozklad.contracts.events.OrderPayload;

/** Store of published orders taken from {@code order.published} (only PUBLISHED ones). */
@Service
public class PublishedOrders {

    private final PublishedOrderRepository repository;
    private final JsonMapper json;
    private final Clock clock;

    public PublishedOrders(PublishedOrderRepository repository, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
    }

    /** @throws IllegalArgumentException for a draft — drafts are never served publicly */
    @Transactional
    public PublishedOrder store(OrderPayload order, UUID eventId) {
        if (order.status() != OrderStatus.PUBLISHED) {
            throw new IllegalArgumentException("Order " + order.id() + " is " + order.status() + ", not PUBLISHED");
        }
        String hash = hash(order);
        Optional<PublishedOrder> existing = repository.findById(order.id());
        if (existing.isPresent()) {
            existing.get().replace(order, hash, eventId, clock.instant());
            return existing.get();
        }
        return repository.save(new PublishedOrder(order, hash, eventId, clock.instant()));
    }

    @Transactional(readOnly = true)
    public Optional<PublishedOrder> find(UUID orderId) {
        return repository.findById(orderId);
    }

    /** Number of the main order an amendment belongs to, when that order is known here. */
    @Transactional(readOnly = true)
    public Integer parentNumber(OrderPayload order) {
        if (order.parentId() == null) {
            return null;
        }
        return repository.findById(order.parentId()).map(PublishedOrder::getNumber).orElse(null);
    }

    /** SHA-256 of the payload JSON: cache key and ETag of rendered files. */
    public String hash(OrderPayload order) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(order));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
