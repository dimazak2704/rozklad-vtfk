package ua.vtc.rozklad.core.accounting.internal.web;

import ua.vtc.rozklad.core.catalog.TeacherRef;

/** Row of {@code GET /api/admin/accounting/teachers?month=}: substitutions done and own pairs missed. */
public record TeacherStatsDto(TeacherRef teacher, int substitutionsCount, int substitutionsHours, int missedCount) {
}
