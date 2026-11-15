package ua.vtc.rozklad.core.load.internal;

import static ua.vtc.rozklad.core.shared.persistence.EntityGuards.checkVersion;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.core.calendar.CalendarQueries;
import ua.vtc.rozklad.core.calendar.SemesterInfo;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.GroupInfo;
import ua.vtc.rozklad.core.load.ControlForm;
import ua.vtc.rozklad.core.load.LoadItem;
import ua.vtc.rozklad.core.load.LoadItemRepository;
import ua.vtc.rozklad.core.load.LoadSource;
import ua.vtc.rozklad.core.load.PlanSource;
import ua.vtc.rozklad.core.load.internal.LoadDtos.LoadItemDto;
import ua.vtc.rozklad.core.load.internal.LoadDtos.LoadItemRequest;
import ua.vtc.rozklad.core.load.internal.LoadDtos.LoadTeacherRequest;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.FieldErrors;
import ua.vtc.rozklad.core.shared.error.NotFoundException;

/**
 * Навантаження CRUD. One item per group × discipline × lesson kind × split; a split item has a
 * teacher per subgroup of the group (catalog), a whole-group item at most one teacher (subgroup 0).
 * Editing an item imported or derived from a timetable makes it MANUAL, so a later re-derivation
 * does not overwrite the admin's numbers. Planned hours given in the request are FIXED; without them the system counts
 * the plan from the group's theory days and keeps it current (AUTO, {@link LoadPlanner}).
 */
@Service
@Transactional(readOnly = true)
public class LoadService {

    private final LoadItemRepository items;
    private final CatalogQueries catalog;
    private final CalendarQueries calendar;
    private final LoadItemMapper mapper;
    private final LoadPlanner planner;

    public LoadService(LoadItemRepository items, CatalogQueries catalog, CalendarQueries calendar,
                       LoadItemMapper mapper, LoadPlanner planner) {
        this.items = items;
        this.catalog = catalog;
        this.calendar = calendar;
        this.mapper = mapper;
        this.planner = planner;
    }

    public List<LoadItemDto> list(UUID semesterId, UUID groupId) {
        List<LoadItem> found = groupId == null
                ? items.findAllBySemesterId(semesterId)
                : items.findAllBySemesterIdAndGroupId(semesterId, groupId);
        return mapper.toDtos(found);
    }

    public LoadItemDto get(UUID id) {
        return mapper.toDto(find(id));
    }

    @Transactional
    public LoadItemDto create(LoadItemRequest request) {
        if (request.semesterId() != null) {
            items.lockSemesterLoad(request.semesterId());
        }
        Context context = validate(request, null);
        LoadItem item = new LoadItem(request.semesterId(), request.groupId(), request.disciplineId(),
                request.hoursPerWeek(), 0);
        copy(request, item);
        if (request.plannedHours() != null) {
            item.fixPlan(request.plannedHours());
        } else {
            item.autoPlan(autoPlan(item, context.siblings()));
        }
        items.save(item);
        return mapper.toDto(item);
    }

    @Transactional
    public LoadItemDto update(UUID id, LoadItemRequest request) {
        LoadItem item = find(id);
        checkVersion(item, request.version());
        FieldErrors errors = new FieldErrors();
        if (!item.getSemesterId().equals(request.semesterId())) {
            errors.add("semesterId", "Семестр навантаження змінити не можна: створіть новий запис.");
        }
        if (!item.getGroupId().equals(request.groupId())) {
            errors.add("groupId", "Групу навантаження змінити не можна: створіть новий запис.");
        }
        errors.throwIfAny();
        items.lockSemesterLoad(item.getSemesterId());
        Context context = validate(request, id);
        boolean fixed = fixesPlan(request, item);
        item.setDisciplineId(request.disciplineId());
        item.setHours(request.hoursPerWeek(), item.getPlannedHours());
        copy(request, item);
        if (fixed) {
            item.fixPlan(request.plannedHours());
        } else {
            item.autoPlan(autoPlan(item, context.siblings()));
        }
        item.setSource(LoadSource.MANUAL);
        items.flush();
        return mapper.toDto(item);
    }

    @Transactional
    public void delete(UUID id) {
        // template_lesson.load_item_id має ON DELETE SET NULL: розклад лишається, втрачається лише зв'язок
        items.delete(find(id));
    }

    LoadItem find(UUID id) {
        return items.findWithTeachersById(id).orElseThrow(() -> NotFoundException.of("Запис навантаження", id));
    }

    private Context validate(LoadItemRequest request, UUID ownId) {
        FieldErrors errors = new FieldErrors();
        SemesterInfo semester = calendar.semester(request.semesterId()).orElse(null);
        if (semester == null) {
            errors.add("semesterId", "Семестр не знайдено.");
        }
        GroupInfo group = catalog.group(request.groupId()).orElse(null);
        if (group == null) {
            errors.add("groupId", "Групу не знайдено.");
        }
        if (catalog.discipline(request.disciplineId()).isEmpty()) {
            errors.add("disciplineId", "Дисципліну не знайдено.");
        }
        if (request.preferredRoomId() != null && catalog.room(request.preferredRoomId()).isEmpty()) {
            errors.add("preferredRoomId", "Аудиторію не знайдено.");
        }
        if (group != null) {
            validateTeachers(Boolean.TRUE.equals(request.splitSubgroups()), request.teachers(), group, errors);
        }
        errors.throwIfAny();
        LessonKind kind = request.lessonKind() == null ? LessonKind.MIXED : request.lessonKind();
        boolean split = Boolean.TRUE.equals(request.splitSubgroups());
        List<LoadItem> siblings = items.findAllBySemesterIdAndGroupIdAndDisciplineId(request.semesterId(),
                        request.groupId(), request.disciplineId()).stream()
                .filter(other -> !other.getId().equals(ownId))
                .toList();
        siblings.stream()
                .filter(other -> other.getLessonKind() == kind && other.isSplitSubgroups() == split)
                .findFirst()
                .ifPresent(other -> {
                    throw new ConflictException("У групи %s уже є таке навантаження з цієї дисципліни.".formatted(group.code()));
                });
        return new Context(siblings);
    }

    /** Split: subgroups 1..n that exist in the group; whole group: only subgroup 0. Teachers must exist. */
    private void validateTeachers(boolean split, List<LoadTeacherRequest> teachers, GroupInfo group,
                                  FieldErrors errors) {
        if (split && group.subgroups().isEmpty()) {
            errors.add("splitSubgroups", "Група %s не поділена на підгрупи: спершу додайте підгрупи в каталозі."
                    .formatted(group.code()));
        }
        if (teachers == null) {
            return;
        }
        Set<Integer> seen = new HashSet<>();
        Set<UUID> known = catalog.teacherRefs(teachers.stream().map(LoadTeacherRequest::teacherId)
                .collect(Collectors.toSet())).keySet();
        for (int i = 0; i < teachers.size(); i++) {
            LoadTeacherRequest teacher = teachers.get(i);
            String field = "teachers[%d]".formatted(i);
            if (!seen.add(teacher.subgroup())) {
                errors.add(field + ".subgroup", "Підгрупа %d повторюється.".formatted(teacher.subgroup()));
            }
            if (split && teacher.subgroup() == 0) {
                errors.add(field + ".subgroup", "Для поділеної групи вкажіть номер підгрупи (1, 2, …).");
            } else if (split && !group.subgroups().contains(teacher.subgroup())) {
                errors.add(field + ".subgroup", "У групі %s немає підгрупи %d.".formatted(group.code(), teacher.subgroup()));
            } else if (!split && teacher.subgroup() != 0) {
                errors.add(field + ".subgroup", "Для всієї групи підгрупа — 0.");
            }
            if (!known.contains(teacher.teacherId())) {
                errors.add(field + ".teacherId", "Викладача не знайдено.");
            }
        }
    }

    private static void copy(LoadItemRequest request, LoadItem item) {
        item.setLessonKind(request.lessonKind() == null ? LessonKind.MIXED : request.lessonKind());
        item.setSplitSubgroups(Boolean.TRUE.equals(request.splitSubgroups()));
        item.setRequiredRoomType(request.requiredRoomType());
        item.setPreferredRoomId(request.preferredRoomId());
        item.setControlForm(request.controlForm() == null ? ControlForm.NONE : request.controlForm());
        item.setStreamId(request.streamId());
        item.setNote(request.note() == null || request.note().isBlank() ? null : request.note().strip());
        Map<Integer, UUID> wanted = request.teachers() == null ? Map.of() : request.teachers().stream()
                .collect(Collectors.toMap(LoadTeacherRequest::subgroup, LoadTeacherRequest::teacherId));
        item.getTeachers().stream().map(link -> link.getSubgroupNumber())
                .filter(subgroup -> !wanted.containsKey(subgroup))
                .toList()
                .forEach(item::unassignTeacher);
        wanted.forEach(item::assignTeacher);
    }

    /**
     * A number in the request fixes the plan. A client that sends back the number of an AUTO plan unchanged (an older
     * form, the undo journal) decides nothing, so the plan stays AUTO; with other hours per week it is a new number.
     */
    private static boolean fixesPlan(LoadItemRequest request, LoadItem item) {
        if (request.plannedHours() == null) {
            return false;
        }
        boolean echo = item.getPlanSource() == PlanSource.AUTO && request.plannedHours() == item.getPlannedHours()
                && item.getHoursPerWeek().compareTo(request.hoursPerWeek()) == 0;
        return !echo;
    }

    /** Planned hours without a number: hours per week over the group's theory days ({@link LoadPlan}, DOMAIN §4). */
    private int autoPlan(LoadItem item, List<LoadItem> siblings) {
        return planner.context(item.getSemesterId(), List.of(item.getGroupId())).plannedHours(item, siblings);
    }

    /** @param siblings the other stored items of the same group × discipline in the semester */
    private record Context(List<LoadItem> siblings) {
    }
}
