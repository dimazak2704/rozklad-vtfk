package ua.vtc.rozklad.core.calendar;

import java.time.LocalDate;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;

/**
 * Read model of a semester for other modules.
 *
 * @param weeksCount    planning weeks set by the admin (curriculum: «17 тиж.»), may be {@code null}
 * @param teachingWeeks {@code weeksCount} or, when it is not set, the Monday-based weeks the dates
 *                      span; used for default planned hours (DOMAIN §4)
 * @param bellProfileId bell profile of the semester; {@code null} = the default from settings
 */
public record SemesterInfo(
        UUID id,
        String name,
        int number,
        String academicYear,
        LocalDate startsOn,
        LocalDate endsOn,
        Parity firstWeekParity,
        Integer weeksCount,
        int teachingWeeks,
        UUID bellProfileId,
        boolean active) {

    public boolean contains(LocalDate date) {
        return !date.isBefore(startsOn) && !date.isAfter(endsOn);
    }
}
