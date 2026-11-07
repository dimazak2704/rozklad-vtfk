package ua.vtc.rozklad.core.substitution.internal.support;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.calendar.BellProfile;
import ua.vtc.rozklad.core.calendar.BellProfileRepository;
import ua.vtc.rozklad.core.calendar.BellSlot;
import ua.vtc.rozklad.core.calendar.BellSlotRepository;
import ua.vtc.rozklad.core.calendar.CalendarPeriod;
import ua.vtc.rozklad.core.calendar.CalendarPeriodKind;
import ua.vtc.rozklad.core.calendar.CalendarPeriodRepository;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.calendar.ShiftWindowRepository;
import ua.vtc.rozklad.core.calendar.StudyMode;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.time.StudyWeeks;

/**
 * Calendar facts the substitution module needs (DOMAIN §2): the semester of a date, periods
 * without classes, online study mode, bell times and shift windows. When the shift window table
 * is empty the DOMAIN defaults are used (shift 1: pairs 1–4; shift 2: 5–8 Mon–Thu, 4–6 Fri).
 */
@Component
public class SubstitutionCalendar {

    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final SemesterRepository semesters;
    private final CalendarPeriodRepository periods;
    private final BellProfileRepository bellProfiles;
    private final BellSlotRepository bellSlots;
    private final ShiftWindowRepository shiftWindows;

    public SubstitutionCalendar(SemesterRepository semesters, CalendarPeriodRepository periods,
                                BellProfileRepository bellProfiles, BellSlotRepository bellSlots,
                                ShiftWindowRepository shiftWindows) {
        this.semesters = semesters;
        this.periods = periods;
        this.bellProfiles = bellProfiles;
        this.bellSlots = bellSlots;
        this.shiftWindows = shiftWindows;
    }

    public Optional<Semester> findSemester(LocalDate date) {
        return semesters.findContaining(date);
    }

    /** The semester of a working day; 422 for weekends and dates outside every semester. */
    public Semester requireStudyDate(LocalDate date) {
        if (!StudyWeeks.isWeekday(date)) {
            throw new BusinessRuleException("NOT_A_STUDY_DAY",
                    "%s — вихідний день: заняття проводяться з понеділка по п'ятницю.".formatted(date.format(DATE)));
        }
        return semesters.findContaining(date).orElseThrow(() -> new BusinessRuleException("DATE_OUTSIDE_SEMESTER",
                "Дата %s не входить у жоден семестр.".formatted(date.format(DATE))));
    }

    public List<CalendarPeriod> periods(Semester semester, LocalDate from, LocalDate to) {
        return periods.findOverlapping(semester.getId(), from, to);
    }

    /** Title of the holiday / no-classes / practice period that cancels the group's lessons on the date. */
    public static Optional<String> noClassesReason(List<CalendarPeriod> periods, StudentGroup group,
                                                   LocalDate date) {
        return periods.stream()
                .filter(period -> period.covers(date))
                .filter(period -> switch (period.getKind()) {
                    case HOLIDAY, NO_CLASSES, PRACTICE -> appliesTo(period, group);
                    case STUDY_MODE, EXAM_SESSION -> false;
                })
                .map(CalendarPeriod::getTitle)
                .findFirst();
    }

    /** {@code true} when an ONLINE study-mode period covers the group on the date. */
    public static boolean onlineFor(List<CalendarPeriod> periods, StudentGroup group, LocalDate date) {
        return periods.stream().anyMatch(period -> period.getKind() == CalendarPeriodKind.STUDY_MODE
                && period.getStudyMode() == StudyMode.ONLINE && period.covers(date) && appliesTo(period, group));
    }

    public static boolean appliesTo(CalendarPeriod period, StudentGroup group) {
        return switch (period.getScope()) {
            case ALL -> true;
            case COURSES -> period.getCourses().contains(group.getCourse());
            case GROUPS -> period.getGroupIds().contains(group.getId());
        };
    }

    /** Bell slots of the date by pair number; empty when no bell profile is known. */
    public Map<Integer, BellSlot> pairTimes(Semester semester, LocalDate date) {
        if (!StudyWeeks.isWeekday(date)) {
            return Map.of();
        }
        return bellProfileId(semester)
                .map(profileId -> bellSlots.findDaySlots(profileId, date.getDayOfWeek().getValue()).stream()
                        .collect(Collectors.toMap(BellSlot::getPairNumber, Function.identity())))
                .orElse(Map.of());
    }

    /** Pairs a group of the shift may have on the weekday (1 = Monday). */
    public PairRange shiftWindow(int shift, int weekday) {
        return shiftWindows.findByShiftAndWeekday(shift, weekday)
                .map(window -> new PairRange(window.getFirstPair(), window.getLastPair()))
                .orElseGet(() -> defaultShiftWindow(shift, weekday));
    }

    static PairRange defaultShiftWindow(int shift, int weekday) {
        if (shift == 1) {
            return new PairRange(1, 4);
        }
        return weekday == DayOfWeek.FRIDAY.getValue() ? new PairRange(4, 6) : new PairRange(5, 8);
    }

    /** The semester's bell profile, otherwise the only active profile. */
    private Optional<UUID> bellProfileId(Semester semester) {
        if (semester.getBellProfileId() != null) {
            return Optional.of(semester.getBellProfileId());
        }
        List<BellProfile> active = bellProfiles.findAllByOrderByName().stream().filter(BellProfile::isActive).toList();
        return active.size() == 1 ? Optional.of(active.getFirst().getId()) : Optional.empty();
    }

    /** Inclusive range of pair numbers. */
    public record PairRange(int first, int last) {

        public boolean contains(int pair) {
            return pair >= first && pair <= last;
        }
    }
}
