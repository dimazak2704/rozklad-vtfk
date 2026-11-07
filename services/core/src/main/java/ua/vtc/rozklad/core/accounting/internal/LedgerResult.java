package ua.vtc.rozklad.core.accounting.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.core.accounting.AccountingStatus;

/**
 * Outcome of {@link HoursLedger#compute}: hours, status, forecast finish date and the suggested
 * action (DOMAIN §9).
 *
 * @param forecastFinishDate date of the lesson on which the cumulative hours reach the plan;
 *                           {@code null} when the scheduled lessons never reach it
 * @param suggestion         what the dispatcher could do about an overrun or underrun, or {@code null}
 */
public record LedgerResult(
        int plan,
        int fact,
        int remaining,
        int forecast,
        AccountingStatus status,
        LocalDate forecastFinishDate,
        Suggestion suggestion) {

    /**
     * DISCIPLINE_FINISHED: cancel the future lessons beyond the plan (their ids are listed, starting
     * at {@code fromDate}); ADDITIONAL: add {@code lessons} pairs because the forecast stays below
     * the plan.
     */
    public record Suggestion(Kind kind, String text, LocalDate fromDate, int lessons, List<UUID> lessonIds) {

        public Suggestion {
            lessonIds = List.copyOf(lessonIds);
        }

        public enum Kind {
            DISCIPLINE_FINISHED,
            ADDITIONAL
        }
    }
}
