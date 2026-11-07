package ua.vtc.rozklad.core.order.internal.view;

import java.util.List;
import ua.vtc.rozklad.contracts.events.OrderPayload;
import ua.vtc.rozklad.core.order.OrderDocument;
import ua.vtc.rozklad.core.order.OrderItem;
import ua.vtc.rozklad.core.order.OrderSection;
import ua.vtc.rozklad.core.order.internal.draft.OrderTexts;

/** Maps an order (with its rows loaded) to the REST DTO and to the event payload. */
public final class OrderViews {

    private OrderViews() {
    }

    public static OrderDto toDto(OrderDocument order) {
        return new OrderDto(order.getId(), order.getNumber(), order.getDate(), order.getKind(), order.getParentId(),
                order.getStatus(), order.getTitle(), order.getWeekdayLabel(), order.getParityLabel(),
                items(order, OrderSection.CHANGES), items(order, OrderSection.ROOM_CHANGES), order.getNotes(),
                order.getSignatoryPosition(), order.getSignatoryName(), OrderTexts.SUBTITLE,
                OrderTexts.dateLine(order.getDate()), order.getPublishedAt(), order.getPublishedBy(),
                order.getVersion());
    }

    /** {@code OrderPayload} of {@code order.published} without the distribution lists. */
    public static OrderPayload toPayload(OrderDocument order, String semesterName,
                                         List<OrderPayload.AffectedGroup> groups,
                                         List<OrderPayload.AffectedTeacher> teachers) {
        return new OrderPayload(order.getId(), order.getNumber(), order.getDate(), order.getKind(),
                order.getParentId(), order.getStatus(), order.getTitle(), order.getWeekdayLabel(),
                order.getParityLabel(), payloadItems(order, OrderSection.CHANGES),
                payloadItems(order, OrderSection.ROOM_CHANGES), order.getNotes(), order.getSignatoryPosition(),
                order.getSignatoryName(), semesterName, groups, teachers);
    }

    private static List<OrderDto.Item> items(OrderDocument order, OrderSection section) {
        return order.getItems(section).stream().map(OrderViews::item).toList();
    }

    private static OrderDto.Item item(OrderItem item) {
        return new OrderDto.Item(item.getId(), item.getGroupCode(), item.getPairLabel(), item.getRowType(),
                item.getFromDiscipline(), item.getFromTeacher(), item.getReasonText(), item.getToDiscipline(),
                item.getToTeacher(), item.getRoomText(), item.getChangeId());
    }

    private static List<OrderPayload.Item> payloadItems(OrderDocument order, OrderSection section) {
        return order.getItems(section).stream()
                .map(item -> new OrderPayload.Item(item.getId(), item.getGroupCode(), item.getPairLabel(),
                        item.getRowType(), item.getFromDiscipline(), item.getFromTeacher(), item.getReasonText(),
                        item.getToDiscipline(), item.getToTeacher(), item.getRoomText(), item.getChangeId()))
                .toList();
    }
}
