package ua.vtc.rozklad.core.order.internal.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.Parity;

/**
 * {@code GET /api/admin/dashboard?date}. {@code aiSpendToday} is always {@code null}: AI usage is
 * accounted by the importer service (see {@code GET /api/import/ai/usage}).
 */
public record DashboardDto(
        LocalDate date,
        Parity parity,
        int absences,
        int uncoveredLessons,
        int pending,
        int confirmed,
        int declined,
        OrderDraft orderDraft,
        Generation generation,
        BigDecimal aiSpendToday,
        List<Alert> alerts) {

    public record OrderDraft(UUID id, long itemCount) {
    }

    public record Generation(GenerationStatus status) {
    }

    /** {@code kind} is a stable code, {@code link} a SPA route with the date. */
    public record Alert(String kind, Severity severity, String message, String link) {
    }

    public enum Severity {
        INFO,
        WARN,
        ERROR
    }
}
