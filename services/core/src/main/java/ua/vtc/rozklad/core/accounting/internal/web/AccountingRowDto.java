package ua.vtc.rozklad.core.accounting.internal.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.core.accounting.AccountingStatus;
import ua.vtc.rozklad.core.catalog.DisciplineRef;
import ua.vtc.rozklad.core.catalog.GroupRef;

/**
 * Row of {@code GET /api/admin/accounting}; hours are academic hours.
 *
 * @param hoursPerWeek sum of the hours per week of the discipline's load items (additive, 15.11.2026)
 * @param planSource   AUTO (the system counts the plan over the group's theory days), FIXED (a set number) or MIXED
 */
public record AccountingRowDto(
        GroupRef group,
        DisciplineRef discipline,
        int plan,
        int fact,
        int remaining,
        int forecast,
        AccountingStatus status,
        LocalDate forecastFinishDate,
        SuggestionDto suggestion,
        BigDecimal hoursPerWeek,
        String planSource) {

    /**
     * {@code kind} DISCIPLINE_FINISHED lists the future lessons to finish ({@code lessonIds},
     * starting at {@code fromDate}); ADDITIONAL says how many pairs to add.
     */
    public record SuggestionDto(String kind, String text, LocalDate fromDate, int lessons, List<UUID> lessonIds) {
    }
}
