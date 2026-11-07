package ua.vtc.rozklad.core.accounting.internal;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A SCHEDULED lesson of one discipline of a group as the ledger sees it. {@code ended} means the
 * pair is over (date in the past, or today after the bell).
 */
public record LedgerLesson(UUID lessonId, LocalDate date, int pair, int subgroup, boolean ended) {
}
