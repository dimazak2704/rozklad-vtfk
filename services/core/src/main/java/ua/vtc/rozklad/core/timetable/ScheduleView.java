package ua.vtc.rozklad.core.timetable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.calendar.PeriodEffect;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.RoomRef;
import ua.vtc.rozklad.core.catalog.TeacherRef;

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
     * @param weekday   1 (Monday) … 5 (Friday)
     * @param studyDay  {@code false} on days off, outside the semester and (for a group) during a block
     *                  (a period of a SUSPEND kind: practice, НС, сесія)
     * @param note      titles of the periods of the day, e.g. «Навчання дистанційно»
     * @param practices blocks of the day (periods of a SUSPEND kind; the name is kept for compatibility): for a
     *                  group the blocks it is at, for a teacher the blocks they supervise; empty for rooms,
     *                  days off and ordinary days (additive)
     */
    public record Day(LocalDate date, int weekday, String weekdayLabel, Parity parity, boolean studyDay, String note,
                      List<LessonView> lessons, List<Practice> practices) {

        public Day {
            lessons = List.copyOf(lessons);
            practices = practices == null ? List.of() : List.copyOf(practices);
        }

        /** A day without practice. */
        public Day(LocalDate date, int weekday, String weekdayLabel, Parity parity, boolean studyDay, String note,
                   List<LessonView> lessons) {
            this(date, weekday, weekdayLabel, parity, studyDay, note, lessons, List.of());
        }
    }

    /**
     * A block of the day (DOMAIN §2): a period of a SUSPEND kind (practice, НС, сесія), its title and dates,
     * the supervising teachers (керівники, possibly none yet) and whom it is for: {@code groups} for a
     * group-scoped period, {@code courses} for a course-scoped one, both empty for the whole college.
     * {@code kind} (additive, always present) names the kind as the college writes it; {@code room}
     * (additive) is where the group is, {@code null} when not given.
     */
    public record Practice(UUID periodId, String title, LocalDate startsOn, LocalDate endsOn,
                           List<TeacherRef> supervisors, List<GroupRef> groups, List<Integer> courses,
                           Kind kind, RoomRef room) {

        public Practice {
            supervisors = List.copyOf(supervisors);
            groups = List.copyOf(groups);
            courses = List.copyOf(courses);
        }
    }

    /** {@code {code, name, color, effect}} of the kind of a block («НС», «Національний спротив»). */
    public record Kind(String code, String name, String color, PeriodEffect effect) {
    }
}
