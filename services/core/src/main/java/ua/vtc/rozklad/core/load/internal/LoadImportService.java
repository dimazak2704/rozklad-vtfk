package ua.vtc.rozklad.core.load.internal;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.core.calendar.CalendarQueries;
import ua.vtc.rozklad.core.calendar.SemesterInfo;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.DisciplineRef;
import ua.vtc.rozklad.core.catalog.GroupInfo;
import ua.vtc.rozklad.core.catalog.RoomInfo;
import ua.vtc.rozklad.core.catalog.TeacherInfo;
import ua.vtc.rozklad.core.load.ControlForm;
import ua.vtc.rozklad.core.load.LoadItem;
import ua.vtc.rozklad.core.load.LoadItemRepository;
import ua.vtc.rozklad.core.load.internal.LoadDtos.LoadImportItem;
import ua.vtc.rozklad.core.load.internal.LoadDtos.LoadImportRequest;
import ua.vtc.rozklad.core.load.internal.LoadDtos.LoadImportResult;
import ua.vtc.rozklad.core.load.internal.LoadDtos.LoadImportTeacher;
import ua.vtc.rozklad.core.shared.error.FieldErrors;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;

/**
 * {@code POST /api/admin/import/load}: upsert of load items by group × discipline × lesson kind ×
 * split, referring to the catalog by natural keys (group code, short names, room name — aliases
 * and DOMAIN §10 spelling variants are accepted). All or nothing: any unknown reference rejects
 * the whole request with field errors {@code items[i]…}.
 */
@Service
public class LoadImportService {

    private final LoadItemRepository items;
    private final CatalogQueries catalog;
    private final CalendarQueries calendar;

    public LoadImportService(LoadItemRepository items, CatalogQueries catalog, CalendarQueries calendar) {
        this.items = items;
        this.catalog = catalog;
        this.calendar = calendar;
    }

    @Transactional
    public LoadImportResult apply(LoadImportRequest request) {
        SemesterInfo semester = calendar.semester(request.semesterId())
                .orElseThrow(() -> FieldValidationException.of("semesterId", "Семестр не знайдено."));
        FieldErrors errors = new FieldErrors();
        List<Resolved> resolved = new java.util.ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < request.items().size(); i++) {
            resolve(request.items().get(i), "items[%d]".formatted(i), errors).ifPresent(item -> {
                if (seen.add(item.naturalKey())) {
                    resolved.add(item);
                } else {
                    errors.add("items", "Рядок «%s» повторюється в запиті.".formatted(item.naturalKey()));
                }
            });
        }
        errors.throwIfAny();

        List<LoadItem> existing = new java.util.ArrayList<>(items.findAllBySemesterId(semester.id()));
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        Map<String, UUID> ids = new LinkedHashMap<>();
        for (Resolved item : resolved) {
            int plannedHours = item.source().plannedHours() != null ? item.source().plannedHours()
                    : LoadService.defaultPlannedHours(item.source().hoursPerWeek(), semester.teachingWeeks());
            Optional<LoadItem> match = existing.stream()
                    .filter(candidate -> candidate.getGroupId().equals(item.group().id())
                            && candidate.getDisciplineId().equals(item.discipline().id())
                            && candidate.getLessonKind() == item.lessonKind()
                            && candidate.isSplitSubgroups() == item.split())
                    .findFirst();
            LoadItem target;
            if (match.isEmpty()) {
                target = new LoadItem(semester.id(), item.group().id(), item.discipline().id(),
                        item.source().hoursPerWeek(), plannedHours);
                apply(item, target);
                items.save(target);
                existing.add(target);
                created++;
            } else {
                target = match.get();
                if (differs(item, target, plannedHours)) {
                    target.setHours(item.source().hoursPerWeek(), plannedHours);
                    apply(item, target);
                    updated++;
                } else {
                    unchanged++;
                }
            }
            ids.put(item.naturalKey(), target.getId());
        }
        items.flush();
        return new LoadImportResult(created, updated, unchanged, ids);
    }

    private Optional<Resolved> resolve(LoadImportItem item, String field, FieldErrors errors) {
        Optional<GroupInfo> group = catalog.groupByCode(item.groupCode());
        if (group.isEmpty()) {
            errors.add(field + ".groupCode", "Групу «%s» не знайдено.".formatted(item.groupCode()));
        }
        Optional<DisciplineRef> discipline = catalog.disciplineByShortName(item.disciplineShortName());
        if (discipline.isEmpty()) {
            errors.add(field + ".disciplineShortName", "Дисципліну «%s» не знайдено.".formatted(item.disciplineShortName()));
        }
        UUID roomId = null;
        if (item.preferredRoomName() != null && !item.preferredRoomName().isBlank()) {
            Optional<RoomInfo> room = catalog.roomByName(item.preferredRoomName());
            if (room.isEmpty()) {
                errors.add(field + ".preferredRoomName", "Аудиторію «%s» не знайдено.".formatted(item.preferredRoomName()));
            } else {
                roomId = room.get().id();
            }
        }
        boolean split = Boolean.TRUE.equals(item.splitSubgroups());
        Map<Integer, UUID> teachers = new TreeMap<>();
        List<LoadImportTeacher> requestedTeachers = item.teachers() == null ? List.of() : item.teachers();
        for (int t = 0; t < requestedTeachers.size(); t++) {
            LoadImportTeacher teacher = requestedTeachers.get(t);
            String teacherField = "%s.teachers[%d]".formatted(field, t);
            Optional<TeacherInfo> found = catalog.teacherByShortName(teacher.teacherShortName());
            if (found.isEmpty()) {
                errors.add(teacherField + ".teacherShortName", "Викладача «%s» не знайдено."
                        .formatted(teacher.teacherShortName()));
            } else if (teachers.putIfAbsent(teacher.subgroup(), found.get().id()) != null) {
                errors.add(teacherField + ".subgroup", "Підгрупа %d повторюється.".formatted(teacher.subgroup()));
            }
            if (group.isPresent() && split && !group.get().subgroups().contains(teacher.subgroup())) {
                errors.add(teacherField + ".subgroup", "У групі %s немає підгрупи %d."
                        .formatted(group.get().code(), teacher.subgroup()));
            }
            if (!split && teacher.subgroup() != 0) {
                errors.add(teacherField + ".subgroup", "Для всієї групи підгрупа — 0.");
            }
        }
        if (group.isEmpty() || discipline.isEmpty()) {
            return Optional.empty();
        }
        LessonKind kind = item.lessonKind() == null ? LessonKind.MIXED : item.lessonKind();
        return Optional.of(new Resolved(item, group.get(), discipline.get(), kind, split, teachers, roomId));
    }

    private static boolean differs(Resolved item, LoadItem target, int plannedHours) {
        Map<Integer, UUID> current = new TreeMap<>();
        target.getTeachers().forEach(link -> current.put(link.getSubgroupNumber(), link.getTeacherId()));
        LoadImportItem source = item.source();
        return target.getHoursPerWeek().compareTo(source.hoursPerWeek()) != 0
                || target.getPlannedHours() != plannedHours
                || target.getSource() != source.source()
                || !current.equals(item.teachers())
                || target.getControlForm() != controlForm(source)
                || target.getRequiredRoomType() != source.requiredRoomType()
                || !java.util.Objects.equals(target.getPreferredRoomId(), item.preferredRoomId())
                || !java.util.Objects.equals(target.getNote(), blankToNull(source.note()));
    }

    private static void apply(Resolved item, LoadItem target) {
        LoadImportItem source = item.source();
        target.setLessonKind(item.lessonKind());
        target.setSplitSubgroups(item.split());
        target.setSource(source.source());
        target.setControlForm(controlForm(source));
        target.setRequiredRoomType(source.requiredRoomType());
        target.setPreferredRoomId(item.preferredRoomId());
        target.setNote(blankToNull(source.note()));
        target.getTeachers().stream().map(link -> link.getSubgroupNumber())
                .filter(subgroup -> !item.teachers().containsKey(subgroup))
                .toList()
                .forEach(target::unassignTeacher);
        item.teachers().forEach(target::assignTeacher);
    }

    private static ControlForm controlForm(LoadImportItem item) {
        return item.controlForm() == null ? ControlForm.NONE : item.controlForm();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private record Resolved(LoadImportItem source, GroupInfo group, DisciplineRef discipline, LessonKind lessonKind,
                            boolean split, Map<Integer, UUID> teachers, UUID preferredRoomId) {

        String naturalKey() {
            return "%s | %s | %s | %s".formatted(group.code(), discipline.shortName(), lessonKind,
                    split ? "split" : "whole");
        }
    }
}
