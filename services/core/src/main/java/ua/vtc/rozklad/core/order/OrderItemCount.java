package ua.vtc.rozklad.core.order;

import java.util.UUID;

/** Row count of an order (projection of {@link OrderDocumentRepository#countItems}). */
public record OrderItemCount(UUID orderId, long items) {
}
