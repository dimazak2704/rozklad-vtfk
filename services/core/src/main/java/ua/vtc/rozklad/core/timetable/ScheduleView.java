package ua.vtc.rozklad.core.timetable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;

/**
 * {@code ScheduleDto} of CONTRACTS.md §1: study days (Monday–Friday) of a date range with their
 * lessons. {@code parity} is NUMERATOR or DENOMINATOR, {@code null} for a day outside every semester.
 */
public record ScheduleView(Owner owner, LocalDate from, LocalDate to, List<Day> days) {

    public ScheduleView {
        days = List.copyOf(days);
    }

    public record Owner(ScheduleOwnerType type, UUID id, String label) {
    }

    /**
     * @param weekday  1 (Monday) … 5 (Friday)
     * @param studyDay {@code false} on holidays, outside the semester and (for a group) during practice
     * @param note     holiday, practice or study-mode note, e.g. «Навчання дистанційно»
     */
    public record Day(LocalDate date, int weekday, String weekdayLabel, Parity parity, boolean studyDay, String note,
                      List<LessonView> lessons) {

        public Day {
            lessons = List.copyOf(lessons);
        }
    }
}
