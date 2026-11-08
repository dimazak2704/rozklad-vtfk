package ua.vtc.rozklad.importer.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.importer.order.OrderDraft.Section;

/**
 * {@code PUT /api/import/jobs/{id}/draft} of an ORDER_DOCUMENT job: the whole order as the admin checked
 * it against the original. Limits follow core's {@code POST /api/admin/import/changes}; {@code rowType}
 * may be omitted (derived from the texts), {@code version} is the job's version from GET.
 */
public record OrderDraftEdit(
        @NotNull(message = "Оновіть сторінку: бракує версії завдання") Long version,
        @Min(1) @Max(9999) Integer number,
        OrderKind kind,
        @Min(1) @Max(9999) Integer parentNumber,
        LocalDate date,
        @Size(max = 20) String weekdayLabel,
        @Size(max = 20) String parityLabel,
        @NotNull @Size(max = 300) List<@Valid @NotNull Row> rows,
        @Size(max = 30) List<@NotBlank @Size(max = 1000) String> notes,
        @Size(max = 200) String signatoryPosition,
        @Size(max = 200) String signatoryName) {

    public record Row(
            Section section,
            @NotBlank(message = "Вкажіть групу") @Size(max = 16) String groupCode,
            @NotBlank(message = "Вкажіть пару") @Size(max = 16) String pairLabel,
            OrderRowType rowType,
            @Size(max = 300) String fromDiscipline,
            @Size(max = 200) String fromTeacher,
            @Size(max = 100) String reasonText,
            @Size(max = 300) String toDiscipline,
            @Size(max = 200) String toTeacher,
            @Size(max = 100) String roomText) {
    }
}
