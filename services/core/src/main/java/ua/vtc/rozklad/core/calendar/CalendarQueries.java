package ua.vtc.rozklad.core.calendar;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ua.vtc.rozklad.contracts.Parity;

/**
 * Calendar rules of DOMAIN §2 for other modules. All dates are Kyiv dates. Methods that need a
 * semester ({@link #parity}, {@link #weekNumber}) throw a 422 {@code DATE_OUTSIDE_SEMESTER} for a
 * date no semester contains; {@link #week} is the non-throwing variant.
 */
public interface CalendarQueries {

    Optional<SemesterInfo> activeSemester();

    Optional<SemesterInfo> semester(UUID id);

    /** The semester whose dates contain {@code date}. */
    Optional<SemesterInfo> semesterOn(LocalDate date);

    Optional<WeekInfo> week(LocalDate date);

    /** NUMERATOR or DENOMINATOR of the week of {@code date} (override wins). */
    Parity parity(LocalDate date);

    int weekNumber(LocalDate date);

    /**
     * {@code true} for Mon–Fri inside a semester and outside every HOLIDAY / NO_CLASSES period
     * that applies to everybody (scope ALL).
     */
    boolean isStudyDay(LocalDate date);

    /**
     * Study day for one group: additionally no HOLIDAY, NO_CLASSES or PRACTICE period whose scope
     * includes the group (all, its course or the group itself).
     */
    boolean isGroupStudyDay(LocalDate date, UUID groupId);

    /** Study mode of a STUDY_MODE period for the group (groups scope wins over courses over all); OFFLINE by default. */
    StudyMode studyModeFor(LocalDate date, UUID groupId);

    /** Periods covering {@code date} that apply to the group, ordered by start. */
    List<PeriodInfo> periodsOn(LocalDate date, UUID groupId);

    /** Order notes of all periods active on {@code date} (DOMAIN §8), ordered by start. */
    List<String> orderNotes(LocalDate date);

    /** Bell profile in effect: the semester's profile, otherwise the default from settings. */
    Optional<BellProfileInfo> bellProfileOn(LocalDate date);

    /** Pair times of the date's weekday from the profile in effect; empty on weekends. */
    List<PairTime> bellSlots(LocalDate date);

    Optional<PairTime> pairTime(LocalDate date, int pair);

    /** The pair that goes on at {@code instant} (Kyiv time) on a study day. */
    Optional<CurrentPair> currentPair(Instant instant);

    /** The next pair of the same day after {@code instant}. */
    Optional<PairTime> nextPair(Instant instant);

    /**
     * @throws ua.vtc.rozklad.core.shared.error.NotFoundException when the window is not configured
     */
    ShiftWindowInfo shiftWindow(int shift, int weekday);

    List<ShiftWindowInfo> shiftWindows();

    /**
     * The whole shift-window table for rule engines (conflicts, generation, make-up slots). When the
     * reference table is empty the DOMAIN §2 windows are used and the result says so.
     */
    ShiftWindows shiftWindowTable();

    /**
     * Calendar facts (parity, study days, periods) of every semester overlapping {@code [from, to]},
     * loaded with a constant number of queries — use it instead of per-date calls over ranges.
     */
    StudyCalendar studyCalendar(LocalDate from, LocalDate to);

    /**
     * @throws ua.vtc.rozklad.core.shared.error.NotFoundException when the semester does not exist
     */
    StudyCalendar studyCalendar(UUID semesterId);

    /** Bell times of every semester of the calendar (semester id → times; none without a profile). */
    Map<UUID, BellTimes> bellTimes(StudyCalendar calendar);

    /** Profile reference for DTOs. */
    record BellProfileInfo(UUID id, String name) {
    }
}
