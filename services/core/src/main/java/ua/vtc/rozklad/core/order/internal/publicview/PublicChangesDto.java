package ua.vtc.rozklad.core.order.internal.publicview;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.contracts.Parity;

/**
 * {@code GET /api/public/changes?date=}: the published changes of a date grouped by group. Only
 * published orders (main + amendments) are public; {@code parity} is {@code null} outside every
 * semester. {@code from}/{@code to} are the printed text of the order row. Additive:
 * {@code amendments} (published amendments of the date by number, each with its own documents),
 * {@code ChangeView.orderNumber} (the order or amendment the row is printed in) and {@code studyDay}
 * ({@code false} on weekends, holidays of the whole college and outside every semester — the regular
 * timetable does not run that day, so «no order yet» does not mean «classes as usual»).
 */
public record PublicChangesDto(
        LocalDate date,
        Parity parity,
        boolean studyDay,
        String weekdayLabel,
        PublishedOrder order,
        List<PublishedOrder> amendments,
        List<GroupChanges> groups) {

    /**
     * {@code imported} (additive): a paper order imported as text; it has no DOCX/PDF/PNG and
     * {@code publishedAt} is when it was imported, not when the college issued it.
     */
    public record PublishedOrder(UUID id, Integer number, Instant publishedAt, boolean imported) {
    }

    public record GroupChanges(ChangeGroup group, List<ChangeView> items) {
    }

    /**
     * {@code GroupRef} shape; {@code id} is {@code null} when a row of an imported paper order names
     * a group that is not in the catalog (the printed code is kept).
     */
    public record ChangeGroup(UUID id, String code, int course) {
    }

    /** {@code ChangeView} of CONTRACTS.md §1. */
    public record ChangeView(String pairLabel, OrderRowType rowType, Side from, String reasonText, Side to,
                             String room, String subgroupNote, Integer orderNumber) {
    }

    public record Side(String discipline, String teacher) {
    }
}
