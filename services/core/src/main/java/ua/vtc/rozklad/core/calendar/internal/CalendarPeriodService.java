package ua.vtc.rozklad.core.calendar.internal;

import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.checkVersion;

import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.calendar.CalendarPeriod;
import ua.vtc.rozklad.core.calendar.CalendarPeriodKind;
import ua.vtc.rozklad.core.calendar.CalendarPeriodRepository;
import ua.vtc.rozklad.core.calendar.PeriodScope;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.calendar.StudyCalendar;
import ua.vtc.rozklad.core.calendar.internal.CalendarChangePublisher.PeriodSnapshot;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.CalendarPeriodDto;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.CalendarPeriodRequest;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.catalog.UkrainianOrder;
import ua.vtc.rozklad.core.shared.error.FieldErrors;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;

/**
 * Календарні періоди: holidays, days without classes, practice, study mode (online/mixed) and
 * exam sessions, for everybody, for courses or for listed groups. A period lies inside its
 * semester; every change publishes {@code schedule.changed} for the affected dates and groups.
 */
@Service
@Transactional(readOnly = true)
public class CalendarPeriodService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final CalendarPeriodRepository periods;
    private final SemesterRepository semesters;
    private final CatalogQueries catalog;
    private final CalendarChangePublisher changes;

    public CalendarPeriodService(CalendarPeriodRepository periods, SemesterRepository semesters,
                                 CatalogQueries catalog, CalendarChangePublisher changes) {
        this.periods = periods;
        this.semesters = semesters;
        this.catalog = catalog;
        this.changes = changes;
    }

    /** Periods of a semester (all semesters when {@code semesterId} is {@code null}) by start date. */
    public List<CalendarPeriodDto> list(UUID semesterId) {
        List<CalendarPeriod> found = semesterId == null
                ? periods.findAllByOrderByStartsOn()
                : periods.findAllBySemesterIdOrderByStartsOn(semesterId);
        Map<UUID, GroupRef> groups = catalog.groupRefs(found.stream()
                .flatMap(period -> period.getGroupIds().stream()).collect(Collectors.toSet()));
        Set<UUID> supervisorIds = found.stream().filter(CalendarPeriodService::isPractice)
                .flatMap(period -> period.getSupervisorIds().stream()).collect(Collectors.toSet());
        Map<UUID, TeacherRef> teachers = supervisorIds.isEmpty() ? Map.of() : catalog.teacherRefs(supervisorIds);
        return found.stream().map(period -> toDto(period, groups, teachers)).toList();
    }

    public CalendarPeriodDto get(UUID id) {
        return toDto(find(id));
    }

    @Transactional
    public CalendarPeriodDto create(CalendarPeriodRequest request) {
        Semester semester = semester(request.semesterId());
        validate(request, semester);
        StudyCalendar before = changes.before(semester.getStartsOn(), semester.getEndsOn());
        CalendarPeriod period = new CalendarPeriod(semester.getId(), request.kind(), request.title().strip(),
                request.startsOn(), request.endsOn());
        copy(request, period);
        periods.save(period);
        changes.periodsChanged(List.of(PeriodSnapshot.of(period)), semester.getStartsOn(), semester.getEndsOn(),
                before);
        return toDto(period);
    }

    @Transactional
    public CalendarPeriodDto update(UUID id, CalendarPeriodRequest request) {
        CalendarPeriod period = find(id);
        checkVersion(period, request.version());
        if (!period.getSemesterId().equals(request.semesterId())) {
            new FieldErrors().add("semesterId", "Період не можна перенести в інший семестр: створіть новий.").throwIfAny();
        }
        Semester semester = semester(period.getSemesterId());
        validate(request, semester);
        StudyCalendar calendarBefore = changes.before(semester.getStartsOn(), semester.getEndsOn());
        PeriodSnapshot before = PeriodSnapshot.of(period);
        period.setKind(request.kind());
        period.setTitle(request.title().strip());
        period.setPeriod(request.startsOn(), request.endsOn());
        copy(request, period);
        periods.flush();
        changes.periodsChanged(List.of(before, PeriodSnapshot.of(period)), semester.getStartsOn(),
                semester.getEndsOn(), calendarBefore);
        return toDto(period);
    }

    @Transactional
    public void delete(UUID id) {
        CalendarPeriod period = find(id);
        Semester semester = semester(period.getSemesterId());
        PeriodSnapshot removed = PeriodSnapshot.of(period);
        StudyCalendar before = changes.before(semester.getStartsOn(), semester.getEndsOn());
        periods.delete(period);
        // слухачі (матеріалізація занять) читають календар уже без цього періоду
        periods.flush();
        changes.periodsChanged(List.of(removed), semester.getStartsOn(), semester.getEndsOn(), before);
    }

    private CalendarPeriod find(UUID id) {
        return periods.findById(id).orElseThrow(() -> NotFoundException.of("Період календаря", id));
    }

    private Semester semester(UUID id) {
        return semesters.findById(id)
                .orElseThrow(() -> FieldValidationException.of("semesterId", "Семестр не знайдено."));
    }

    private void validate(CalendarPeriodRequest request, Semester semester) {
        FieldErrors errors = new FieldErrors();
        if (request.endsOn().isBefore(request.startsOn())) {
            errors.add("endsOn", "Кінець періоду не може бути раніше за початок.");
        }
        if (request.startsOn().isBefore(semester.getStartsOn()) || request.endsOn().isAfter(semester.getEndsOn())) {
            errors.add("startsOn", "Період має бути в межах семестру (%s–%s).".formatted(
                    DATE.format(semester.getStartsOn()), DATE.format(semester.getEndsOn())));
        }
        if (request.kind() == CalendarPeriodKind.STUDY_MODE && request.studyMode() == null) {
            errors.add("studyMode", "Для періоду форми навчання вкажіть режим: офлайн, онлайн або змішаний.");
        }
        if (request.kind() != CalendarPeriodKind.STUDY_MODE && request.studyMode() != null) {
            errors.add("studyMode", "Режим навчання задають лише для періоду форми навчання.");
        }
        PeriodScope scope = scopeOf(request);
        List<Integer> courses = request.courses() == null ? List.of() : request.courses();
        List<UUID> groupIds = request.groupIds() == null ? List.of() : request.groupIds();
        switch (scope) {
            case ALL -> {
                if (!courses.isEmpty() || !groupIds.isEmpty()) {
                    errors.add("scope", "Для періоду «для всіх» не вказують курси чи групи.");
                }
            }
            case COURSES -> {
                if (courses.isEmpty()) {
                    errors.add("courses", "Вкажіть хоча б один курс.");
                }
                if (!groupIds.isEmpty()) {
                    errors.add("groupIds", "Для періоду за курсами групи не вказують.");
                }
            }
            case GROUPS -> {
                if (groupIds.isEmpty()) {
                    errors.add("groupIds", "Вкажіть хоча б одну групу.");
                }
                if (!courses.isEmpty()) {
                    errors.add("courses", "Для періоду за групами курси не вказують.");
                }
                Set<UUID> known = catalog.groupRefs(groupIds).keySet();
                for (int i = 0; i < groupIds.size(); i++) {
                    if (!known.contains(groupIds.get(i))) {
                        errors.add("groupIds[%d]".formatted(i), "Групу не знайдено.");
                    }
                }
            }
        }
        validateSupervisors(request, errors);
        errors.throwIfAny();
    }

    /** Керівники практики: only for PRACTICE, each an existing active teacher, named once. */
    private void validateSupervisors(CalendarPeriodRequest request, FieldErrors errors) {
        List<UUID> supervisorIds = request.supervisorIds() == null ? List.of() : request.supervisorIds();
        if (supervisorIds.isEmpty()) {
            return;
        }
        if (request.kind() != CalendarPeriodKind.PRACTICE) {
            errors.add("supervisorIds", "Керівників призначають лише для періоду практики.");
            return;
        }
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < supervisorIds.size(); i++) {
            String field = "supervisorIds[%d]".formatted(i);
            UUID teacherId = supervisorIds.get(i);
            if (!seen.add(teacherId)) {
                errors.add(field, "Цього викладача вже призначено керівником.");
                continue;
            }
            catalog.teacher(teacherId).ifPresentOrElse(teacher -> {
                if (!teacher.active()) {
                    errors.add(field, "Викладач %s неактивний: його не можна призначити керівником."
                            .formatted(teacher.shortName()));
                }
            }, () -> errors.add(field, "Викладача не знайдено."));
        }
    }

    private static void copy(CalendarPeriodRequest request, CalendarPeriod period) {
        period.setStudyMode(request.studyMode());
        period.setOrderNote(request.orderNote() == null || request.orderNote().isBlank() ? null : request.orderNote().strip());
        switch (scopeOf(request)) {
            case ALL -> period.applyToAll();
            case COURSES -> period.applyToCourses(request.courses());
            case GROUPS -> period.applyToGroups(new LinkedHashSet<>(request.groupIds()));
        }
        period.setSupervisors(request.supervisorIds());
    }

    private static PeriodScope scopeOf(CalendarPeriodRequest request) {
        return request.scope() == null ? PeriodScope.ALL : request.scope();
    }

    private CalendarPeriodDto toDto(CalendarPeriod period) {
        Set<UUID> supervisorIds = isPractice(period) ? period.getSupervisorIds() : Set.of();
        return toDto(period, catalog.groupRefs(period.getGroupIds()),
                supervisorIds.isEmpty() ? Map.of() : catalog.teacherRefs(supervisorIds));
    }

    private static boolean isPractice(CalendarPeriod period) {
        return period.getKind() == CalendarPeriodKind.PRACTICE;
    }

    private static CalendarPeriodDto toDto(CalendarPeriod period, Map<UUID, GroupRef> groups,
                                           Map<UUID, TeacherRef> teachers) {
        List<GroupRef> refs = period.getGroupIds().stream()
                .map(groups::get)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(GroupRef::code, UkrainianOrder.NATURAL))
                .toList();
        Collection<UUID> groupIds = refs.stream().map(GroupRef::id).toList();
        List<TeacherRef> supervisors = !isPractice(period) ? List.of() : period.getSupervisorIds().stream()
                .map(teachers::get)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(TeacherRef::shortName, UkrainianOrder.NATURAL))
                .toList();
        return new CalendarPeriodDto(period.getId(), period.getSemesterId(), period.getKind(), period.getTitle(),
                period.getStartsOn(), period.getEndsOn(), period.getStudyMode(), period.getOrderNote(), period.getScope(),
                period.getCourses(), List.copyOf(groupIds), refs, period.getVersion(),
                supervisors.stream().map(TeacherRef::id).toList(), supervisors);
    }
}
