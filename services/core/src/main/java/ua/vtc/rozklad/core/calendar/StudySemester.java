package ua.vtc.rozklad.core.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;

/**
 * Dates, week parity and calendar periods of one semester, loaded once for a {@link StudyCalendar}.
 *
 * @param weeks         week numbering and parity (overrides included)
 * @param bellProfileId bell profile of the semester, {@code null} when not set
 */
public record StudySemester(
        UUID semesterId,
        LocalDate startsOn,
        LocalDate endsOn,
        SemesterCalendar weeks,
        List<StudyPeriod> periods,
        UUID bellProfileId) {

    public StudySemester {
        Objects.requireNonNull(semesterId, "semesterId");
        Objects.requireNonNull(startsOn, "startsOn");
        Objects.requireNonNull(endsOn, "endsOn");
        Objects.requireNonNull(weeks, "weeks");
        periods = List.copyOf(periods);
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(startsOn) && !date.isAfter(endsOn);
    }

    public Parity parity(LocalDate date) {
        return weeks.parity(date);
    }

    public int weekNumber(LocalDate date) {
        return weeks.weekNumber(date);
    }
}
