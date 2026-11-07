package ua.vtc.rozklad.core.accounting.internal;

import java.util.UUID;
import ua.vtc.rozklad.core.accounting.DisciplineProgress;

/** Ledger of one group × discipline of a semester. */
public record AccountingRow(UUID groupId, UUID disciplineId, LedgerResult ledger) {

    public DisciplineProgress progress() {
        return new DisciplineProgress(groupId, disciplineId, ledger.plan(), ledger.fact(), ledger.remaining(),
                ledger.forecast(), ledger.status(), ledger.forecastFinishDate());
    }
}
