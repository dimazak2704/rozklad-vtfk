package ua.vtc.rozklad.core.order.internal.publicview;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.catalog.GroupRef;

/**
 * {@code GET /api/public/changes?date=}: the published changes of a date grouped by group. Only
 * published orders (main + amendments) are public; {@code parity} is {@code null} outside every
 * semester. {@code from}/{@code to} are the printed text of the order row.
 */
public record PublicChangesDto(
        LocalDate date,
        Parity parity,
        String weekdayLabel,
        PublishedOrder order,
        List<GroupChanges> groups) {

    public record PublishedOrder(UUID id, Integer number, Instant publishedAt) {
    }

    public record GroupChanges(GroupRef group, List<ChangeView> items) {
    }

    /** {@code ChangeView} of CONTRACTS.md §1. */
    public record ChangeView(String pairLabel, OrderRowType rowType, Side from, String reasonText, Side to,
                             String room, String subgroupNote) {
    }

    public record Side(String discipline, String teacher) {
    }
}
