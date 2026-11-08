package ua.vtc.rozklad.core.order.internal.importing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.core.order.OrderSection;

/**
 * {@code POST /api/admin/import/changes}: a paper order that was already issued (recognized by the
 * importer from a photo, scan or DOCX), stored as its printed text. Field limits follow the
 * {@code order_document} and {@code order_item} columns. {@code replace = true}: the rows correct an
 * earlier import of the same order (same number, date and kind), which is then replaced; an order
 * the system published itself is never replaced.
 */
public record HistoricOrderRequest(
        @NotNull(message = "Вкажіть дату розпорядження") LocalDate date,
        @NotNull(message = "Вкажіть номер розпорядження") @Min(1) @Max(9999) Integer number,
        OrderKind kind,
        @Min(1) @Max(9999) Integer parentNumber,
        @Size(max = 20) String weekdayLabel,
        @Size(max = 20) String parityLabel,
        @NotNull @Size(max = 300) List<@Valid @NotNull Item> items,
        @Size(max = 30) List<@NotBlank @Size(max = 1000) String> notes,
        @Size(max = 200) String signatoryPosition,
        @Size(max = 200) String signatoryName,
        Boolean replace) {

    /** One printed row; {@code section} is the main table unless it is a room change. */
    public record Item(
            OrderSection section,
            @NotBlank(message = "Вкажіть групу") @Size(max = 16) String groupCode,
            @NotBlank(message = "Вкажіть пару") @Size(max = 16) String pairLabel,
            @NotNull OrderRowType rowType,
            @Size(max = 300) String fromDiscipline,
            @Size(max = 200) String fromTeacher,
            @Size(max = 100) String reasonText,
            @Size(max = 300) String toDiscipline,
            @Size(max = 200) String toTeacher,
            @Size(max = 100) String roomText) {
    }

    /**
     * {@code orderId} of the stored order; {@code created == false} when the same order was imported before,
     * {@code replaced == true} when its rows were replaced by this request ({@code replace}).
     */
    public record Result(UUID orderId, int number, LocalDate date, OrderKind kind, int itemCount,
                         boolean created, boolean replaced) {
    }
}
