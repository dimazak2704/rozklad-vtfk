package ua.vtc.rozklad.core.order;

import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.OrderRowType;

/** Printed values of one order row (all text is stored as is); {@code changeId} may be {@code null}. */
public record OrderItemContent(
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

    public OrderItemContent {
        Objects.requireNonNull(groupCode, "groupCode");
        Objects.requireNonNull(pairLabel, "pairLabel");
        Objects.requireNonNull(rowType, "rowType");
    }
}
