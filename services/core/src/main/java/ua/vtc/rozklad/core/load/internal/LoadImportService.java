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
 * <p>
 * {@code splitSubgroups} absent ({@code null}) means «the item that is there»: a curriculum or a program
 * row does not know whether the group studies the discipline by subgroups, so it updates the only item of
 * that group × discipline × lesson kind and keeps its split (a new item is for the whole group). When the
 * semester has both a whole-group and a split item for it the row is refused ({@code items[i].splitSubgroups}).
 * <p>
 * An update keeps what the request does not carry: a field that is absent ({@code null}) leaves the
 * stored value as it is, so a program or a curriculum (hours only) does not erase the teachers, the
 * room requirements or the note of an item derived from the timetable. An explicit empty list of
 * teachers or a blank room name / note clears the value.
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
        List<LoadItem> existing = new java.util.ArrayList<>(items.findAllBySemesterId(semester.id()));
        FieldErrors errors = new FieldErrors();
        List<Resolved> resolved = new java.util.ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < request.items().size(); i++) {
            resolve(request.items().get(i), "items[%d]".formatted(i), existing, errors).ifPresent(item -> {
                if (seen.add(item.naturalKey())) {
                    resolved.add(item);
                } else {
                    errors.add("items", "Рядок «%s» повторюється в запиті.".formatted(item.naturalKey()));
                }
            });
        }
        errors.throwIfAny();

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

    private Optional<Resolved> resolve(LoadImportItem item, String field, List<LoadItem> existing,
                                       FieldErrors errors) {
        Optional<GroupInfo> group = catalog.groupByCode(item.groupCode());
        if (group.isEmpty()) {
            errors.add(field + ".groupCode", "Групу «%s» не знайдено.".formatted(item.groupCode()));
        }
        Optional<DisciplineRef> discipline = catalog.disciplineByShortName(item.disciplineShortName());
        if (discipline.isEmpty()) {
            errors.add(field + ".disciplineShortName", "Дисципліну «%s» не знайдено.".formatted(item.disciplineShortName()));
        }
        Optional<UUID> roomId = item.preferredRoomName() == null ? null : Optional.empty();
        if (item.preferredRoomName() != null && !item.preferredRoomName().isBlank()) {
            Optional<RoomInfo> room = catalog.roomByName(item.preferredRoomName());
            if (room.isEmpty()) {
                errors.add(field + ".preferredRoomName", "Аудиторію «%s» не знайдено.".formatted(item.preferredRoomName()));
            } else {
                roomId = Optional.of(room.get().id());
            }
        }
        LessonKind kind = item.lessonKind() == null ? LessonKind.MIXED : item.lessonKind();
        boolean split;
        if (item.splitSubgroups() != null) {
            split = item.splitSubgroups();
        } else if (group.isEmpty() || discipline.isEmpty()) {
            split = false;
        } else {
            Set<Boolean> stored = storedSplits(existing, group.get(), discipline.get(), kind);
            if (stored.size() > 1) {
                errors.add(field + ".splitSubgroups", ("Група %s має з «%s» два рядки навантаження: для всієї групи "
                        + "й по підгрупах. Уточніть, який оновити.").formatted(group.get().code(),
                        discipline.get().shortName()));
                return Optional.empty();
            }
            split = stored.contains(true);
        }
        // null = викладачів у запиті немає (програма, навчальний план): наявні призначення лишаються
        Map<Integer, UUID> teachers = item.teachers() == null ? null : new TreeMap<>();
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
        return Optional.of(new Resolved(item, group.get(), discipline.get(), kind, split, teachers, roomId));
    }

    /** The split states of the stored items of group × discipline × lesson kind (none, one or both). */
    private static Set<Boolean> storedSplits(List<LoadItem> existing, GroupInfo group, DisciplineRef discipline,
                                             LessonKind kind) {
        Set<Boolean> splits = new HashSet<>();
        existing.stream()
                .filter(candidate -> candidate.getGroupId().equals(group.id())
                        && candidate.getDisciplineId().equals(discipline.id())
                        && candidate.getLessonKind() == kind)
                .forEach(candidate -> splits.add(candidate.isSplitSubgroups()));
        return splits;
    }

    private static boolean differs(Resolved item, LoadItem target, int plannedHours) {
        LoadImportItem source = item.source();
        return target.getHoursPerWeek().compareTo(source.hoursPerWeek()) != 0
                || target.getPlannedHours() != plannedHours
                || target.getSource() != source.source()
                || (item.teachers() != null && !teachersOf(target).equals(item.teachers()))
                || (source.controlForm() != null && target.getControlForm() != source.controlForm())
                || (source.requiredRoomType() != null && target.getRequiredRoomType() != source.requiredRoomType())
                || (item.preferredRoomId() != null
                && !java.util.Objects.equals(target.getPreferredRoomId(), item.preferredRoomId().orElse(null)))
                || (source.note() != null && !java.util.Objects.equals(target.getNote(), blankToNull(source.note())));
    }

    /** Copies what the request carries; absent ({@code null}) fields keep the stored values. */
    private static void apply(Resolved item, LoadItem target) {
        LoadImportItem source = item.source();
        target.setLessonKind(item.lessonKind());
        target.setSplitSubgroups(item.split());
        target.setSource(source.source());
        if (source.controlForm() != null) {
            target.setControlForm(source.controlForm());
        }
        if (source.requiredRoomType() != null) {
            target.setRequiredRoomType(source.requiredRoomType());
        }
        if (item.preferredRoomId() != null) {
            target.setPreferredRoomId(item.preferredRoomId().orElse(null));
        }
        if (source.note() != null) {
            target.setNote(blankToNull(source.note()));
        }
        if (item.teachers() != null) {
            target.getTeachers().stream().map(link -> link.getSubgroupNumber())
                    .filter(subgroup -> !item.teachers().containsKey(subgroup))
                    .toList()
                    .forEach(target::unassignTeacher);
            item.teachers().forEach(target::assignTeacher);
        }
    }

    private static Map<Integer, UUID> teachersOf(LoadItem target) {
        Map<Integer, UUID> current = new TreeMap<>();
        target.getTeachers().forEach(link -> current.put(link.getSubgroupNumber(), link.getTeacherId()));
        return current;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /**
     * {@code teachers == null}: the request has no teachers; {@code preferredRoomId == null}: no room
     * name in the request, {@code Optional.empty()}: a blank name (clear the room).
     */
    private record Resolved(LoadImportItem source, GroupInfo group, DisciplineRef discipline, LessonKind lessonKind,
                            boolean split, Map<Integer, UUID> teachers, Optional<UUID> preferredRoomId) {

        String naturalKey() {
            return "%s | %s | %s | %s".formatted(group.code(), discipline.shortName(), lessonKind,
                    split ? "split" : "whole");
        }
    }
}
