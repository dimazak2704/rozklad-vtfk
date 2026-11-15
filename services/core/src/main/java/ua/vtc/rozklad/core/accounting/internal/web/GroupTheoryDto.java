package ua.vtc.rozklad.core.accounting.internal.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.core.calendar.PeriodEffect;
import ua.vtc.rozklad.core.catalog.GroupRef;

/**
 * Element of {@code GET /api/admin/accounting/theory}: how many weeks the group is on theory in the system's semester
 * (owner's rule 15.11.2026: weeks off theory do not count and are not caught up) and what takes it off.
 *
 * @param semesterWeeks    Monday-based weeks of the semester
 * @param theoryWeeks      weeks with at least one study day of the group
 * @param numeratorWeeks   theory weeks with parity NUMERATOR (a numerator-only pair counts only these)
 * @param denominatorWeeks theory weeks with parity DENOMINATOR
 * @param studyDays        Monday–Friday dates the group is on theory
 * @param graphTheoryWeeks theory weeks of the college's academic calendar (cross-check), {@code null} when not imported
 * @param offPeriods       DAY_OFF and SUSPEND periods of the group in the semester, by start
 */
public record GroupTheoryDto(
        GroupRef group,
        int semesterWeeks,
        int theoryWeeks,
        int numeratorWeeks,
        int denominatorWeeks,
        int studyDays,
        Integer graphTheoryWeeks,
        List<OffPeriodDto> offPeriods) {

    /** A period that takes the group off its pairs; {@code kind.code} as in the academic calendar (НС, НП, К…). */
    public record OffPeriodDto(UUID id, KindDto kind, String title, LocalDate startsOn, LocalDate endsOn) {
    }

    public record KindDto(String code, String name, String color, PeriodEffect effect) {
    }
}
