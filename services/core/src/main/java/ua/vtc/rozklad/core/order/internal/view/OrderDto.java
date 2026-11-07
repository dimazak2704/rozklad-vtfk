package ua.vtc.rozklad.core.order.internal.view;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.contracts.OrderStatus;

/**
 * {@code OrderDto} of CONTRACTS.md §2. Additive (CHANGELOG-CONTRACTS): {@code subtitle}
 * («По коледжу про деякі зміни в розкладі занять»), {@code dateLabel} («На 19 жовтня 2026 р.»),
 * {@code publishedAt}, {@code publishedBy} and {@code version}, so every client prints the same
 * college header.
 */
public record OrderDto(
        UUID id,
        Integer number,
        LocalDate date,
        OrderKind kind,
        UUID parentId,
        OrderStatus status,
        String title,
        String weekdayLabel,
        String parityLabel,
        List<Item> items,
        List<Item> roomChanges,
        List<String> notes,
        String signatoryPosition,
        String signatoryName,
        String subtitle,
        String dateLabel,
        Instant publishedAt,
        String publishedBy,
        long version) {

    /** {@code OrderItemDto}: printed text of one row. */
    public record Item(
            UUID id,
            String groupCode,
            String pairLabel,
            OrderRowType rowType,
            String fromDiscipline,
            String fromTeacher,
            String reasonText,
            String toDiscipline,
            String toTeacher,
            String roomText,
            UUID changeId) {
    }

    /** Element of the order list ({@code GET /api/admin/orders}). */
    public record Summary(UUID id, Integer number, LocalDate date, OrderKind kind, OrderStatus status, long itemCount,
                          Instant publishedAt) {
    }
}
