package ua.vtc.rozklad.core.accounting.internal.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.core.accounting.AccountingStatus;
import ua.vtc.rozklad.core.shared.ref.DisciplineRef;
import ua.vtc.rozklad.core.shared.ref.GroupRef;

/** Row of {@code GET /api/admin/accounting}; hours are academic hours. */
public record AccountingRowDto(
        GroupRef group,
        DisciplineRef discipline,
        int plan,
        int fact,
        int remaining,
        int forecast,
        AccountingStatus status,
        LocalDate forecastFinishDate,
        SuggestionDto suggestion) {

    /**
     * {@code kind} DISCIPLINE_FINISHED lists the future lessons to finish ({@code lessonIds},
     * starting at {@code fromDate}); ADDITIONAL says how many pairs to add.
     */
    public record SuggestionDto(String kind, String text, LocalDate fromDate, int lessons, List<UUID> lessonIds) {
    }
}
