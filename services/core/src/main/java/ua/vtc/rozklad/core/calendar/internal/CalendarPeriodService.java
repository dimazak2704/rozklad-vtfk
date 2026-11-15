package ua.vtc.rozklad.core.calendar.internal;

import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.checkVersion;

import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.calendar.CalendarPeriod;
import ua.vtc.rozklad.core.calendar.CalendarPeriodKind;
import ua.vtc.rozklad.core.calendar.CalendarPeriodRepository;
import ua.vtc.rozklad.core.calendar.PeriodEffect;
import ua.vtc.rozklad.core.calendar.PeriodKind;
import ua.vtc.rozklad.core.calendar.PeriodKindRepository;
import ua.vtc.rozklad.core.calendar.PeriodScope;
import ua.vtc.rozklad.core.calendar.PeriodSource;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.calendar.StudyCalendar;
import ua.vtc.rozklad.core.calendar.internal.CalendarChangePublisher.PeriodSnapshot;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.CalendarPeriodDto;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.CalendarPeriodRequest;
import ua.vtc.rozklad.core.calendar.internal.CalendarDtos.PeriodKindRef;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.RoomRef;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.catalog.UkrainianOrder;
import ua.vtc.rozklad.core.shared.error.FieldErrors;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;

/**
 * Календарні періоди: a {@link PeriodKind} (holidays, practice, НС, study mode, сесія…) over dates, for
 * everybody, for courses or for listed groups. A period lies inside its semester; what the form may carry
 * follows the kind's effect (study mode for ONLINE, supervisors and a room for SUSPEND). Every change
 * publishes {@code schedule.changed} for the affected dates and groups. An edit of an imported row makes it
 * MANUAL, so the academic calendar import leaves it alone afterwards.
 */
@Service
@Transactional(readOnly = true)
public class CalendarPeriodService {

    static final String NO_KIND = "Вкажіть вид періоду.";
    static final String UNKNOWN_KIND = "Вид періоду не знайдено.";
    static final String INACTIVE_KIND = "Вид «%s» вимкнено: увімкніть його в «Видах періодів» або виберіть інший.";
    static final String STUDY_MODE_REQUIRED = "Для виду з ефектом «онлайн» вкажіть режим: офлайн, онлайн або змішаний.";
    static final String STUDY_MODE_FORBIDDEN = "Режим навчання задають лише для виду з ефектом «онлайн».";
    static final String ROOM_FORBIDDEN = "Аудиторію вказують лише для виду, коли група не на парах.";
    static final String UNKNOWN_ROOM = "Аудиторію не знайдено.";
    static final String SUPERVISORS_FORBIDDEN =
            "Керівників призначають лише для виду, коли група не на парах (практика, НС…).";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final CalendarPeriodRepository periods;
    private final PeriodKindRepository kinds;
    private final SemesterRepository semesters;
    private final CatalogQueries catalog;
    private final CalendarChangePublisher changes;

    public CalendarPeriodService(CalendarPeriodRepository periods, PeriodKindRepository kinds,
                                 SemesterRepository semesters, CatalogQueries catalog,
                                 CalendarChangePublisher changes) {
        this.periods = periods;
        this.kinds = kinds;
        this.semesters = semesters;
        this.catalog = catalog;
        this.changes = changes;
    }

    /** Periods of a semester (all semesters when {@code semesterId} is {@code null}) by start date. */
    public List<CalendarPeriodDto> list(UUID semesterId) {
        List<CalendarPeriod> found = semesterId == null
                ? periods.findAllByOrderByStartsOn()
                : periods.findAllBySemesterIdOrderByStartsOn(semesterId);
        return toDtos(found);
    }

    public CalendarPeriodDto get(UUID id) {
        return toDtos(List.of(find(id))).getFirst();
    }

    @Transactional
    public CalendarPeriodDto create(CalendarPeriodRequest request) {
        Semester semester = semester(request.semesterId());
        PeriodKind kind = validate(request, semester, null);
        StudyCalendar before = changes.before(semester.getStartsOn(), semester.getEndsOn());
        CalendarPeriod period = new CalendarPeriod(semester.getId(), kind, request.title().strip(),
                request.startsOn(), request.endsOn());
        copy(request, period);
        periods.save(period);
        changes.periodsChanged(List.of(PeriodSnapshot.of(period)), semester.getStartsOn(), semester.getEndsOn(),
                before);
        return toDtos(List.of(period)).getFirst();
    }

    @Transactional
    public CalendarPeriodDto update(UUID id, CalendarPeriodRequest request) {
        CalendarPeriod period = find(id);
        checkVersion(period, request.version());
        if (!period.getSemesterId().equals(request.semesterId())) {
            new FieldErrors().add("semesterId", "Період не можна перенести в інший семестр: створіть новий.").throwIfAny();
        }
        Semester semester = semester(period.getSemesterId());
        PeriodKind kind = validate(request, semester, period.getKind());
        StudyCalendar calendarBefore = changes.before(semester.getStartsOn(), semester.getEndsOn());
        PeriodSnapshot before = PeriodSnapshot.of(period);
        period.setKind(kind);
        period.setTitle(request.title().strip());
        period.setPeriod(request.startsOn(), request.endsOn());
        copy(request, period);
        // правка диспетчера: наступний імпорт графіка цей рядок не чіпає
        period.markManual();
        periods.flush();
        changes.periodsChanged(List.of(before, PeriodSnapshot.of(period)), semester.getStartsOn(),
                semester.getEndsOn(), calendarBefore);
        return toDtos(List.of(period)).getFirst();
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
        return periods.findWithKindById(id).orElseThrow(() -> NotFoundException.of("Період календаря", id));
    }

    private Semester semester(UUID id) {
        return semesters.findById(id)
                .orElseThrow(() -> FieldValidationException.of("semesterId", "Семестр не знайдено."));
    }

    /**
     * All field errors of the request at once; returns the resolved kind. {@code current} is the kind the
     * period has now ({@code null} on create): an inactive kind may stay, but cannot be chosen anew.
     */
    private PeriodKind validate(CalendarPeriodRequest request, Semester semester, PeriodKind current) {
        FieldErrors errors = new FieldErrors();
        PeriodKind kind = resolveKind(request, current, errors);
        if (request.endsOn().isBefore(request.startsOn())) {
            errors.add("endsOn", "Кінець періоду не може бути раніше за початок.");
        }
        if (request.startsOn().isBefore(semester.getStartsOn()) || request.endsOn().isAfter(semester.getEndsOn())) {
            errors.add("startsOn", "Період має бути в межах семестру (%s–%s).".formatted(
                    DATE.format(semester.getStartsOn()), DATE.format(semester.getEndsOn())));
        }
        if (kind != null) {
            validateByEffect(request, kind.getEffect(), errors);
        }
        validateScope(request, errors);
        errors.throwIfAny();
        return kind;
    }

    /** {@code kindId} wins; else the legacy {@code kind} → its builtin kind; else 400. */
    private PeriodKind resolveKind(CalendarPeriodRequest request, PeriodKind current, FieldErrors errors) {
        UUID kindId = request.kindId() != null ? request.kindId()
                : request.kind() != null ? request.kind().builtinKindId() : null;
        if (kindId == null) {
            errors.add("kindId", NO_KIND);
            return null;
        }
        PeriodKind kind = kinds.findById(kindId).orElse(null);
        if (kind == null) {
            errors.add("kindId", UNKNOWN_KIND);
            return null;
        }
        boolean chosenAnew = current == null || !current.getId().equals(kind.getId());
        if (chosenAnew && !kind.isActive()) {
            errors.add("kindId", INACTIVE_KIND.formatted(kind.getName()));
        }
        return kind;
    }

    private void validateByEffect(CalendarPeriodRequest request, PeriodEffect effect, FieldErrors errors) {
        if (effect == PeriodEffect.ONLINE && request.studyMode() == null) {
            errors.add("studyMode", STUDY_MODE_REQUIRED);
        }
        if (effect != PeriodEffect.ONLINE && request.studyMode() != null) {
            errors.add("studyMode", STUDY_MODE_FORBIDDEN);
        }
        if (request.roomId() != null) {
            if (effect != PeriodEffect.SUSPEND) {
                errors.add("roomId", ROOM_FORBIDDEN);
            } else if (catalog.room(request.roomId()).isEmpty()) {
                errors.add("roomId", UNKNOWN_ROOM);
            }
        }
        validateSupervisors(request, effect, errors);
    }

    private void validateScope(CalendarPeriodRequest request, FieldErrors errors) {
        List<Integer> courses = request.courses() == null ? List.of() : request.courses();
        List<UUID> groupIds = request.groupIds() == null ? List.of() : request.groupIds();
        switch (scopeOf(request)) {
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
    }

    /** Керівники: only for a SUSPEND kind, each an existing active teacher, named once. */
    private void validateSupervisors(CalendarPeriodRequest request, PeriodEffect effect, FieldErrors errors) {
        List<UUID> supervisorIds = request.supervisorIds() == null ? List.of() : request.supervisorIds();
        if (supervisorIds.isEmpty()) {
            return;
        }
        if (effect != PeriodEffect.SUSPEND) {
            errors.add("supervisorIds", SUPERVISORS_FORBIDDEN);
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
        period.setRoomId(request.roomId());
    }

    private static PeriodScope scopeOf(CalendarPeriodRequest request) {
        return request.scope() == null ? PeriodScope.ALL : request.scope();
    }

    /** DTOs with one batch lookup each for groups, supervisors and rooms (none when nobody needs them). */
    List<CalendarPeriodDto> toDtos(Collection<CalendarPeriod> found) {
        Map<UUID, GroupRef> groups = catalog.groupRefs(found.stream()
                .flatMap(period -> period.getGroupIds().stream()).collect(Collectors.toSet()));
        Set<UUID> supervisorIds = found.stream().filter(CalendarPeriodService::suspends)
                .flatMap(period -> period.getSupervisorIds().stream()).collect(Collectors.toSet());
        Map<UUID, TeacherRef> teachers = supervisorIds.isEmpty() ? Map.of() : catalog.teacherRefs(supervisorIds);
        Set<UUID> roomIds = found.stream().map(CalendarPeriod::getRoomId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, RoomRef> rooms = roomIds.isEmpty() ? Map.of() : catalog.roomRefs(roomIds);
        return found.stream().map(period -> toDto(period, groups, teachers, rooms)).toList();
    }

    private static boolean suspends(CalendarPeriod period) {
        return period.getEffect() == PeriodEffect.SUSPEND;
    }

    private static CalendarPeriodDto toDto(CalendarPeriod period, Map<UUID, GroupRef> groups,
                                           Map<UUID, TeacherRef> teachers, Map<UUID, RoomRef> rooms) {
        List<GroupRef> refs = period.getGroupIds().stream()
                .map(groups::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(GroupRef::code, UkrainianOrder.NATURAL))
                .toList();
        Collection<UUID> groupIds = refs.stream().map(GroupRef::id).toList();
        List<TeacherRef> supervisors = !suspends(period) ? List.of() : period.getSupervisorIds().stream()
                .map(teachers::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(TeacherRef::shortName, UkrainianOrder.NATURAL))
                .toList();
        PeriodKind kind = period.getKind();
        RoomRef room = period.getRoomId() == null ? null : rooms.get(period.getRoomId());
        return new CalendarPeriodDto(period.getId(), period.getSemesterId(), CalendarPeriodKind.of(kind),
                period.getTitle(), period.getStartsOn(), period.getEndsOn(), period.getStudyMode(),
                period.getOrderNote(), period.getScope(), period.getCourses(), List.copyOf(groupIds), refs,
                period.getVersion(), supervisors.stream().map(TeacherRef::id).toList(), supervisors, kind.getId(),
                PeriodKindRef.of(kind), period.getRoomId(), room,
                period.getSource() == null ? PeriodSource.MANUAL : period.getSource(), period.getSourceKey());
    }
}
