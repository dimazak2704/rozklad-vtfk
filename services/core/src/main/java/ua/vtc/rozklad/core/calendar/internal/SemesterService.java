package ua.vtc.rozklad.core.calendar.internal;

import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.checkVersion;
import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.deleteOrConflict;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.calendar.AcademicYear;
import ua.vtc.rozklad.core.calendar.AcademicYearRepository;
import ua.vtc.rozklad.core.calendar.BellProfile;
import ua.vtc.rozklad.core.calendar.BellProfileRepository;
import ua.vtc.rozklad.core.calendar.CalendarPeriod;
import ua.vtc.rozklad.core.calendar.CalendarPeriodRepository;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.calendar.SemesterCalendar;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.calendar.WeekParityOverride;
import ua.vtc.rozklad.core.calendar.WeekParityOverrideRepository;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.SemesterDto;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.SemesterRequest;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.SemesterWeekDto;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.FieldErrors;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.web.Ref;

/**
 * Семестри: CRUD, activation (at most one active), weeks with parity and manual parity overrides.
 * Semesters never overlap, so every date belongs to at most one semester.
 */
@Service
@Transactional(readOnly = true)
public class SemesterService {

    private static final Pattern ACADEMIC_YEAR = Pattern.compile("(\\d{4})/(\\d{4})");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final SemesterRepository semesters;
    private final AcademicYearRepository academicYears;
    private final WeekParityOverrideRepository overrides;
    private final CalendarPeriodRepository periods;
    private final BellProfileRepository bellProfiles;
    private final CalendarChangePublisher changes;

    public SemesterService(SemesterRepository semesters, AcademicYearRepository academicYears,
                           WeekParityOverrideRepository overrides, CalendarPeriodRepository periods,
                           BellProfileRepository bellProfiles, CalendarChangePublisher changes) {
        this.semesters = semesters;
        this.academicYears = academicYears;
        this.overrides = overrides;
        this.periods = periods;
        this.bellProfiles = bellProfiles;
        this.changes = changes;
    }

    /** All semesters, newest first. */
    public List<SemesterDto> list() {
        List<Semester> all = semesters.findAllByOrderByStartsOnDesc();
        Map<UUID, String> years = academicYears.findAll().stream()
                .collect(Collectors.toMap(AcademicYear::getId, AcademicYear::getName));
        Map<UUID, BellProfile> profiles = bellProfiles.findAll().stream()
                .collect(Collectors.toMap(BellProfile::getId, Function.identity()));
        return all.stream().map(semester -> toDto(semester, years.get(semester.getAcademicYearId()),
                profiles.get(semester.getBellProfileId()))).toList();
    }

    public SemesterDto get(UUID id) {
        return toDto(find(id));
    }

    @Transactional
    public SemesterDto create(SemesterRequest request) {
        Values values = validate(request, null);
        Semester semester = new Semester(values.academicYear().getId(), request.number(), values.name(),
                request.startsOn(), request.endsOn());
        values.copyTo(semester, request);
        semesters.save(semester);
        return toDto(semester);
    }

    @Transactional
    public SemesterDto update(UUID id, SemesterRequest request) {
        Semester semester = find(id);
        checkVersion(semester, request.version());
        Values values = validate(request, id);
        ensurePeriodsFit(semester, request.startsOn(), request.endsOn());
        LocalDate oldStart = semester.getStartsOn();
        LocalDate oldEnd = semester.getEndsOn();
        Parity oldParity = semester.getFirstWeekParity();
        semester.setAcademicYearId(values.academicYear().getId());
        semester.setNumber(request.number());
        semester.setName(values.name());
        semester.setPeriod(request.startsOn(), request.endsOn());
        values.copyTo(semester, request);
        dropOverridesOutside(semester);
        semesters.flush();
        if (!oldStart.equals(semester.getStartsOn()) || !oldEnd.equals(semester.getEndsOn())
                || oldParity != semester.getFirstWeekParity()) {
            changes.semesterChanged(oldStart, oldEnd, semester.getStartsOn(), semester.getEndsOn());
        }
        return toDto(semester);
    }

    @Transactional
    public void delete(UUID id) {
        Semester semester = find(id);
        if (semester.isActive()) {
            throw new ConflictException("Активний семестр не можна видалити: спершу активуйте інший.");
        }
        deleteOrConflict(semesters, semester, () -> ("Семестр «%s» не можна видалити: у ньому є навантаження, "
                + "розклад або розпорядження.").formatted(semester.getName()));
    }

    /** Makes the semester the only active one (the public site and defaults use it). */
    @Transactional
    public SemesterDto activate(UUID id) {
        Semester semester = find(id);
        if (!semester.isActive()) {
            semesters.findByActiveTrue().ifPresent(current -> {
                current.setActive(false);
                // унікальний індекс «один активний» не відкладений: спершу знімаємо прапорець у поточного
                semesters.flush();
            });
            semester.setActive(true);
            semesters.flush();
        }
        return toDto(semester);
    }

    public List<SemesterWeekDto> weeks(UUID id) {
        Semester semester = find(id);
        return calendar(semester).weeks().stream().map(SemesterService::weekDto).toList();
    }

    /**
     * Sets ({@code parity} NUMERATOR/DENOMINATOR) or removes ({@code null}) the parity of one week.
     * Only that week changes; the alternation of the other weeks stays.
     */
    @Transactional
    public SemesterWeekDto overrideWeek(UUID id, LocalDate weekStart, Parity parity) {
        Semester semester = find(id);
        SemesterCalendar calendar = calendar(semester);
        FieldErrors errors = new FieldErrors();
        if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            errors.add("weekStart", "Тиждень задається датою його понеділка.");
        } else if (weekStart.isBefore(calendar.firstWeekStart())
                || weekStart.isAfter(SemesterCalendar.weekStartOf(semester.getEndsOn()))) {
            errors.add("weekStart", "Тиждень %s не належить семестру.".formatted(DATE.format(weekStart)));
        }
        if (parity == Parity.BOTH) {
            errors.add("parity", "Парність тижня — чисельник (NUMERATOR) або знаменник (DENOMINATOR).");
        }
        errors.throwIfAny();
        var existing = overrides.findBySemesterIdAndWeekStart(id, weekStart);
        if (parity == null) {
            existing.ifPresent(overrides::delete);
        } else if (existing.isPresent()) {
            existing.get().setParity(parity);
        } else {
            overrides.save(new WeekParityOverride(id, weekStart, parity));
        }
        overrides.flush();
        changes.weekChanged(weekStart, semester.getStartsOn(), semester.getEndsOn());
        return calendar(semester).weeks().stream()
                .filter(week -> week.weekStart().equals(weekStart))
                .map(SemesterService::weekDto)
                .findFirst()
                .orElseThrow();
    }

    Semester find(UUID id) {
        return semesters.findById(id).orElseThrow(() -> NotFoundException.of("Семестр", id));
    }

    private SemesterCalendar calendar(Semester semester) {
        return SemesterCalendar.of(semester, overrides.findAllBySemesterIdOrderByWeekStart(semester.getId()));
    }

    private Values validate(SemesterRequest request, UUID ownId) {
        FieldErrors errors = new FieldErrors();
        if (!request.endsOn().isAfter(request.startsOn())) {
            errors.add("endsOn", "Кінець семестру має бути пізніше за початок.");
        }
        if (request.firstWeekParity() == Parity.BOTH) {
            errors.add("firstWeekParity", "Парність першого тижня — чисельник або знаменник.");
        }
        if (request.bellProfileId() != null && !bellProfiles.existsById(request.bellProfileId())) {
            errors.add("bellProfileId", "Профіль дзвінків не знайдено.");
        }
        String yearName = request.academicYear() != null ? request.academicYear() : deriveAcademicYear(request.startsOn());
        Matcher year = ACADEMIC_YEAR.matcher(yearName);
        if (!year.matches() || Integer.parseInt(year.group(2)) != Integer.parseInt(year.group(1)) + 1) {
            errors.add("academicYear", "Навчальний рік має вигляд 2026/2027 (роки поспіль).");
        }
        errors.throwIfAny();

        AcademicYear academicYear = academicYears.findByName(yearName).orElseGet(() -> {
            int first = Integer.parseInt(year.group(1));
            return academicYears.save(new AcademicYear(yearName, LocalDate.of(first, 9, 1),
                    LocalDate.of(first + 1, 8, 31)));
        });
        semesters.findByAcademicYearIdAndNumber(academicYear.getId(), request.number())
                .filter(existing -> !existing.getId().equals(ownId))
                .ifPresent(existing -> {
                    throw new ConflictException("Семестр %s %s уже існує.".formatted(roman(request.number()), yearName));
                });
        semesters.findAll().stream()
                .filter(other -> !other.getId().equals(ownId))
                .filter(other -> !other.getStartsOn().isAfter(request.endsOn())
                        && !other.getEndsOn().isBefore(request.startsOn()))
                .findFirst()
                .ifPresent(other -> {
                    throw new ConflictException("Дати семестру перетинаються з «%s» (%s–%s).".formatted(other.getName(),
                            DATE.format(other.getStartsOn()), DATE.format(other.getEndsOn())));
                });
        String name = request.name() == null || request.name().isBlank()
                ? roman(request.number()) + " семестр " + yearName
                : request.name().strip();
        return new Values(academicYear, name);
    }

    /** Calendar periods must stay inside the semester, so shrinking it is refused while they stick out. */
    private void ensurePeriodsFit(Semester semester, LocalDate startsOn, LocalDate endsOn) {
        List<CalendarPeriod> outside = periods.findAllBySemesterIdOrderByStartsOn(semester.getId()).stream()
                .filter(period -> period.getStartsOn().isBefore(startsOn) || period.getEndsOn().isAfter(endsOn))
                .toList();
        if (!outside.isEmpty()) {
            throw new ConflictException("Нові дати не охоплюють періоди календаря: %s. Спершу змініть їх."
                    .formatted(outside.stream().map(CalendarPeriod::getTitle).collect(Collectors.joining(", "))));
        }
    }

    private void dropOverridesOutside(Semester semester) {
        LocalDate first = SemesterCalendar.weekStartOf(semester.getStartsOn());
        LocalDate last = SemesterCalendar.weekStartOf(semester.getEndsOn());
        overrides.findAllBySemesterIdOrderByWeekStart(semester.getId()).stream()
                .filter(override -> override.getWeekStart().isBefore(first) || override.getWeekStart().isAfter(last))
                .forEach(overrides::delete);
    }

    private SemesterDto toDto(Semester semester) {
        String year = academicYears.findById(semester.getAcademicYearId()).map(AcademicYear::getName).orElse(null);
        BellProfile profile = semester.getBellProfileId() == null ? null
                : bellProfiles.findById(semester.getBellProfileId()).orElse(null);
        return toDto(semester, year, profile);
    }

    private static SemesterDto toDto(Semester semester, String academicYear, BellProfile profile) {
        return new SemesterDto(semester.getId(), semester.getAcademicYearId(), academicYear, semester.getNumber(),
                semester.getName(), semester.getStartsOn(), semester.getEndsOn(), semester.getFirstWeekParity(),
                semester.getWeeksCount(), CalendarQueryService.teachingWeeks(semester), semester.getBellProfileId(),
                profile == null ? null : new Ref(profile.getId(), profile.getName()), semester.isActive(),
                semester.getVersion());
    }

    private static SemesterWeekDto weekDto(SemesterCalendar.Week week) {
        return new SemesterWeekDto(week.weekStart(), week.weekEnd(), week.number(), week.parity(),
                week.computedParity(), week.overridden());
    }

    /** «2026/2027» for a start in September 2026 (or any month from July). */
    static String deriveAcademicYear(LocalDate startsOn) {
        int first = startsOn.getMonthValue() >= 7 ? startsOn.getYear() : startsOn.getYear() - 1;
        return first + "/" + (first + 1);
    }

    private static String roman(int number) {
        return number == 1 ? "I" : "II";
    }

    private record Values(AcademicYear academicYear, String name) {

        void copyTo(Semester semester, SemesterRequest request) {
            semester.setFirstWeekParity(request.firstWeekParity() == null ? Parity.NUMERATOR : request.firstWeekParity());
            semester.setWeeksCount(request.weeksCount());
            semester.setBellProfileId(request.bellProfileId());
        }
    }
}
