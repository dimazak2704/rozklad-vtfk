package ua.vtc.rozklad.core.calendar.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.calendar.AcademicYear;
import ua.vtc.rozklad.core.calendar.AcademicYearRepository;
import ua.vtc.rozklad.core.calendar.BellProfileRepository;
import ua.vtc.rozklad.core.calendar.BellSlotRepository;
import ua.vtc.rozklad.core.calendar.CalendarPeriod;
import ua.vtc.rozklad.core.calendar.CalendarPeriodRepository;
import ua.vtc.rozklad.core.calendar.CalendarQueries;
import ua.vtc.rozklad.core.calendar.CurrentPair;
import ua.vtc.rozklad.core.calendar.PairTime;
import ua.vtc.rozklad.core.calendar.PeriodInfo;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.calendar.SemesterCalendar;
import ua.vtc.rozklad.core.calendar.SemesterInfo;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.calendar.ShiftWindowInfo;
import ua.vtc.rozklad.core.calendar.ShiftWindowRepository;
import ua.vtc.rozklad.core.calendar.StudyMode;
import ua.vtc.rozklad.core.calendar.WeekInfo;
import ua.vtc.rozklad.core.calendar.WeekParityOverrideRepository;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.GroupInfo;
import ua.vtc.rozklad.core.settings.SettingsService;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.time.TimeConfig;

/** Calendar rules for other modules (see {@link CalendarQueries}). */
@Service
@Transactional(readOnly = true)
class CalendarQueryService implements CalendarQueries {

    static final String DATE_OUTSIDE_SEMESTER = "DATE_OUTSIDE_SEMESTER";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final SemesterRepository semesters;
    private final AcademicYearRepository academicYears;
    private final WeekParityOverrideRepository overrides;
    private final CalendarPeriodRepository periods;
    private final BellProfileRepository bellProfiles;
    private final BellSlotRepository bellSlots;
    private final ShiftWindowRepository shiftWindows;
    private final CatalogQueries catalog;
    private final SettingsService settings;

    CalendarQueryService(SemesterRepository semesters, AcademicYearRepository academicYears,
                         WeekParityOverrideRepository overrides, CalendarPeriodRepository periods,
                         BellProfileRepository bellProfiles, BellSlotRepository bellSlots,
                         ShiftWindowRepository shiftWindows, CatalogQueries catalog, SettingsService settings) {
        this.semesters = semesters;
        this.academicYears = academicYears;
        this.overrides = overrides;
        this.periods = periods;
        this.bellProfiles = bellProfiles;
        this.bellSlots = bellSlots;
        this.shiftWindows = shiftWindows;
        this.catalog = catalog;
        this.settings = settings;
    }

    @Override
    public Optional<SemesterInfo> activeSemester() {
        return semesters.findByActiveTrue().map(this::info);
    }

    @Override
    public Optional<SemesterInfo> semester(UUID id) {
        return semesters.findById(id).map(this::info);
    }

    @Override
    public Optional<SemesterInfo> semesterOn(LocalDate date) {
        return semesters.findContaining(date).map(this::info);
    }

    @Override
    public Optional<WeekInfo> week(LocalDate date) {
        return semesters.findContaining(date).map(semester -> {
            SemesterCalendar calendar = calendar(semester);
            return new WeekInfo(semester.getId(), calendar.weekNumber(date), SemesterCalendar.weekStartOf(date),
                    calendar.parity(date), calendar.isOverridden(date));
        });
    }

    @Override
    public Parity parity(LocalDate date) {
        return week(date).map(WeekInfo::parity).orElseThrow(() -> outsideSemester(date));
    }

    @Override
    public int weekNumber(LocalDate date) {
        return week(date).map(WeekInfo::weekNumber).orElseThrow(() -> outsideSemester(date));
    }

    @Override
    public boolean isStudyDay(LocalDate date) {
        return semesters.findContaining(date)
                .map(semester -> PeriodRules.isStudyDay(date, calendar(semester), periodsOf(semester, date)))
                .orElse(false);
    }

    @Override
    public boolean isGroupStudyDay(LocalDate date, UUID groupId) {
        GroupInfo group = group(groupId);
        return semesters.findContaining(date)
                .map(semester -> PeriodRules.isGroupStudyDay(date, calendar(semester), periodsOf(semester, date),
                        group.id(), group.course()))
                .orElse(false);
    }

    @Override
    public StudyMode studyModeFor(LocalDate date, UUID groupId) {
        GroupInfo group = group(groupId);
        return semesters.findContaining(date)
                .map(semester -> PeriodRules.studyMode(date, periodsOf(semester, date), group.id(), group.course()))
                .orElse(StudyMode.OFFLINE);
    }

    @Override
    public List<PeriodInfo> periodsOn(LocalDate date, UUID groupId) {
        GroupInfo group = group(groupId);
        return semesters.findContaining(date)
                .map(semester -> periodsOf(semester, date).stream()
                        .filter(period -> PeriodRules.appliesTo(period, group.id(), group.course()))
                        .sorted(Comparator.comparing(CalendarPeriod::getStartsOn))
                        .map(PeriodInfo::of)
                        .toList())
                .orElse(List.of());
    }

    @Override
    public List<String> orderNotes(LocalDate date) {
        return semesters.findContaining(date)
                .map(semester -> periodsOf(semester, date).stream()
                        .sorted(Comparator.comparing(CalendarPeriod::getStartsOn))
                        .map(CalendarPeriod::getOrderNote)
                        .filter(Objects::nonNull)
                        .filter(note -> !note.isBlank())
                        .toList())
                .orElse(List.of());
    }

    @Override
    public Optional<BellProfileInfo> bellProfileOn(LocalDate date) {
        UUID profileId = semesters.findContaining(date).map(Semester::getBellProfileId)
                .or(settings::activeBellProfileId)
                .orElse(null);
        if (profileId == null) {
            return Optional.empty();
        }
        return bellProfiles.findById(profileId).map(profile -> new BellProfileInfo(profile.getId(), profile.getName()));
    }

    @Override
    public List<PairTime> bellSlots(LocalDate date) {
        int weekday = date.getDayOfWeek().getValue();
        if (weekday > 5) {
            return List.of();
        }
        return bellProfileOn(date)
                .map(profile -> bellSlots.findDaySlots(profile.id(), weekday).stream()
                        .map(slot -> new PairTime(slot.getPairNumber(), slot.getStartsAt(), slot.getEndsAt()))
                        .toList())
                .orElse(List.of());
    }

    @Override
    public Optional<PairTime> pairTime(LocalDate date, int pair) {
        return bellSlots(date).stream().filter(slot -> slot.pair() == pair).findFirst();
    }

    @Override
    public Optional<CurrentPair> currentPair(Instant instant) {
        ZonedDateTime now = instant.atZone(TimeConfig.KYIV);
        if (!isStudyDay(now.toLocalDate())) {
            return Optional.empty();
        }
        return PairClock.current(bellSlots(now.toLocalDate()), now.toLocalTime());
    }

    @Override
    public Optional<PairTime> nextPair(Instant instant) {
        ZonedDateTime now = instant.atZone(TimeConfig.KYIV);
        if (!isStudyDay(now.toLocalDate())) {
            return Optional.empty();
        }
        return PairClock.next(bellSlots(now.toLocalDate()), now.toLocalTime());
    }

    @Override
    public ShiftWindowInfo shiftWindow(int shift, int weekday) {
        return shiftWindows.findByShiftAndWeekday(shift, weekday)
                .map(ShiftWindowInfo::of)
                .orElseThrow(() -> new NotFoundException(
                        "Вікно зміни %d для дня %d не налаштовано.".formatted(shift, weekday)));
    }

    @Override
    public List<ShiftWindowInfo> shiftWindows() {
        return shiftWindows.findAllByOrderByShiftAscWeekdayAsc().stream().map(ShiftWindowInfo::of).toList();
    }

    SemesterInfo info(Semester semester) {
        String academicYear = academicYears.findById(semester.getAcademicYearId()).map(AcademicYear::getName).orElse(null);
        return new SemesterInfo(semester.getId(), semester.getName(), semester.getNumber(), academicYear,
                semester.getStartsOn(), semester.getEndsOn(), semester.getFirstWeekParity(), semester.getWeeksCount(),
                teachingWeeks(semester), semester.getBellProfileId(), semester.isActive());
    }

    SemesterCalendar calendar(Semester semester) {
        return SemesterCalendar.of(semester, overrides.findAllBySemesterIdOrderByWeekStart(semester.getId()));
    }

    static int teachingWeeks(Semester semester) {
        if (semester.getWeeksCount() != null) {
            return semester.getWeeksCount();
        }
        return new SemesterCalendar(semester.getStartsOn(), semester.getEndsOn(), semester.getFirstWeekParity(),
                java.util.Map.of()).calendarWeeks();
    }

    private List<CalendarPeriod> periodsOf(Semester semester, LocalDate date) {
        return periods.findOverlapping(semester.getId(), date, date);
    }

    private GroupInfo group(UUID groupId) {
        return catalog.group(groupId).orElseThrow(() -> NotFoundException.of("Групу", groupId));
    }

    static BusinessRuleException outsideSemester(LocalDate date) {
        return new BusinessRuleException(DATE_OUTSIDE_SEMESTER,
                "Дата %s не належить жодному семестру.".formatted(DATE.format(date)));
    }
}
