package ua.vtc.rozklad.core.accounting.internal;

import java.math.BigDecimal;
import java.util.UUID;
import ua.vtc.rozklad.core.accounting.DisciplineProgress;

/**
 * Ledger of one group × discipline of a semester.
 *
 * @param hoursPerWeek sum of the hours per week of the load items (a whole-group and a split item add up per cohort)
 * @param planSource   AUTO when the system counts every item's plan, FIXED when every plan is a set number, MIXED
 */
public record AccountingRow(UUID groupId, UUID disciplineId, LedgerResult ledger, BigDecimal hoursPerWeek,
                            String planSource) {

    public DisciplineProgress progress() {
        return new DisciplineProgress(groupId, disciplineId, ledger.plan(), ledger.fact(), ledger.remaining(),
                ledger.forecast(), ledger.status(), ledger.forecastFinishDate());
    }
}
