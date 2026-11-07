package ua.vtc.rozklad.core.order.internal.web;

import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.OrderStatus;
import ua.vtc.rozklad.core.order.OrderDocument;
import ua.vtc.rozklad.core.order.OrderDocumentRepository;
import ua.vtc.rozklad.core.order.OrderItem;
import ua.vtc.rozklad.core.order.OrderItemCount;
import ua.vtc.rozklad.core.order.internal.view.OrderDto;
import ua.vtc.rozklad.core.order.internal.view.OrderViews;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.text.UkrainianText;
import ua.vtc.rozklad.core.shared.web.PageResponse;

/** Order archive: list with filters and search, one order with its rows. */
@Service
@Transactional(readOnly = true)
class OrderQueries {

    private final OrderDocumentRepository orders;

    OrderQueries(OrderDocumentRepository orders) {
        this.orders = orders;
    }

    /**
     * Newest first. {@code q} is an order number («35») or a fragment of a group code or a
     * teacher name in the rows («4КІ1», «кренц»).
     */
    PageResponse<OrderDto.Summary> list(LocalDate from, LocalDate to, String q, OrderStatus status, int page,
                                        int size) {
        List<Specification<OrderDocument>> filters = new ArrayList<>();
        if (from != null) {
            filters.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("date"), from));
        }
        if (to != null) {
            filters.add((root, query, cb) -> cb.lessThanOrEqualTo(root.get("date"), to));
        }
        if (status != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (q != null && !q.isBlank()) {
            filters.add(search(q.strip()));
        }
        Page<OrderDocument> found = orders.findAll(Specification.allOf(filters), PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("date"), Sort.Order.desc("createdAt"))));
        Map<UUID, Long> counts = found.isEmpty() ? Map.of()
                : orders.countItems(found.map(OrderDocument::getId).toList()).stream()
                .collect(Collectors.toMap(OrderItemCount::orderId, OrderItemCount::items));
        return PageResponse.of(found, order -> new OrderDto.Summary(order.getId(), order.getNumber(),
                order.getDate(), order.getKind(), order.getStatus(), counts.getOrDefault(order.getId(), 0L),
                order.getPublishedAt()));
    }

    OrderDto get(UUID id) {
        return OrderViews.toDto(orders.findWithItemsById(id)
                .orElseThrow(() -> NotFoundException.of("Розпорядження", id)));
    }

    private static Specification<OrderDocument> search(String q) {
        if (q.matches("\\d{1,6}")) {
            int number = Integer.parseInt(q);
            return (root, query, cb) -> cb.equal(root.get("number"), number);
        }
        String pattern = "%" + q.toLowerCase(UkrainianText.LOCALE)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return (root, query, cb) -> {
            Subquery<UUID> rows = query.subquery(UUID.class);
            Root<OrderItem> item = rows.from(OrderItem.class);
            rows.select(item.get("id")).where(
                    cb.equal(item.get("order"), root),
                    cb.or(cb.like(cb.lower(item.get("groupCode")), pattern, '\\'),
                            cb.like(cb.lower(item.get("fromTeacher")), pattern, '\\'),
                            cb.like(cb.lower(item.get("toTeacher")), pattern, '\\')));
            return cb.exists(rows);
        };
    }
}
