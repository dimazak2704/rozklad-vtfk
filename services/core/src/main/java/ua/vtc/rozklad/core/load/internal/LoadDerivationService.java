package ua.vtc.rozklad.core.load.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.calendar.CalendarQueries;
import ua.vtc.rozklad.core.calendar.SemesterInfo;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.DisciplineRef;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.load.LoadItem;
import ua.vtc.rozklad.core.load.LoadItemRepository;
import ua.vtc.rozklad.core.load.LoadSource;
import ua.vtc.rozklad.core.load.internal.LoadDerivation.Draft;
import ua.vtc.rozklad.core.load.internal.LoadDerivation.Key;
import ua.vtc.rozklad.core.load.internal.LoadDtos.DerivedLoadResult;
import ua.vtc.rozklad.core.load.internal.LoadDtos.SkippedDerivation;
import ua.vtc.rozklad.core.settings.SettingsService;
import ua.vtc.rozklad.core.shared.error.NotFoundException;

/**
 * {@code POST /api/admin/load/derive-from-version/{versionId}}: replaces the TIMETABLE_DERIVED load
 * of the version's semester with what the version's lessons imply. Load entered by hand or taken
 * from a curriculum or program wins: such a group × discipline is skipped and reported.
 */
@Service
public class LoadDerivationService {

    private final TemplateLessonReader lessons;
    private final LoadItemRepository items;
    private final CalendarQueries calendar;
    private final CatalogQueries catalog;
    private final SettingsService settings;
    private final LoadItemMapper mapper;

    public LoadDerivationService(TemplateLessonReader lessons, LoadItemRepository items, CalendarQueries calendar,
                                 CatalogQueries catalog, SettingsService settings, LoadItemMapper mapper) {
        this.lessons = lessons;
        this.items = items;
        this.calendar = calendar;
        this.catalog = catalog;
        this.settings = settings;
        this.mapper = mapper;
    }

    @Transactional
    public DerivedLoadResult derive(UUID versionId) {
        TemplateLessonReader.VersionLessons version = lessons.read(versionId)
                .orElseThrow(() -> NotFoundException.of("Версію розкладу", versionId));
        SemesterInfo semester = calendar.semester(version.semesterId())
                .orElseThrow(() -> NotFoundException.of("Семестр", version.semesterId()));
        List<Draft> drafts = LoadDerivation.derive(version.lessons(), settings.hoursPerPair());

        List<LoadItem> existing = items.findAllBySemesterId(semester.id());
        Map<Key, LoadItem> derived = existing.stream()
                .filter(item -> item.getSource() == LoadSource.TIMETABLE_DERIVED)
                .collect(Collectors.toMap(LoadDerivationService::keyOf, item -> item, (first, second) -> first));
        Set<List<UUID>> enteredByOthers = existing.stream()
                .filter(item -> item.getSource() != LoadSource.TIMETABLE_DERIVED)
                .map(item -> List.of(item.getGroupId(), item.getDisciplineId()))
                .collect(Collectors.toSet());

        Counters counters = new Counters();
        List<Draft> skippedDrafts = new ArrayList<>();
        Set<Key> produced = new HashSet<>();
        List<LoadItem> result = new ArrayList<>();
        for (Draft draft : drafts) {
            if (enteredByOthers.contains(List.of(draft.groupId(), draft.disciplineId()))) {
                skippedDrafts.add(draft);
                continue;
            }
            produced.add(draft.key());
            LoadItem item = derived.get(draft.key());
            int plannedHours = LoadService.defaultPlannedHours(draft.hoursPerWeek(), semester.teachingWeeks());
            if (item == null) {
                item = new LoadItem(semester.id(), draft.groupId(), draft.disciplineId(), draft.hoursPerWeek(), plannedHours);
                item.setSource(LoadSource.TIMETABLE_DERIVED);
                apply(draft, item);
                items.save(item);
                counters.created++;
            } else if (changes(draft, item, plannedHours)) {
                item.setHours(draft.hoursPerWeek(), plannedHours);
                apply(draft, item);
                counters.updated++;
            }
            result.add(item);
        }
        for (Map.Entry<Key, LoadItem> entry : derived.entrySet()) {
            if (!produced.contains(entry.getKey())) {
                items.delete(entry.getValue());
                counters.removed++;
            }
        }
        items.flush();
        return new DerivedLoadResult(versionId, semester.id(), counters.created, counters.updated, counters.removed,
                skipped(skippedDrafts), mapper.toDtos(result));
    }

    private List<SkippedDerivation> skipped(List<Draft> drafts) {
        Map<UUID, GroupRef> groups = catalog.groupRefs(drafts.stream().map(Draft::groupId).collect(Collectors.toSet()));
        Map<UUID, DisciplineRef> disciplines = catalog.disciplineRefs(drafts.stream().map(Draft::disciplineId)
                .collect(Collectors.toSet()));
        return drafts.stream()
                .map(draft -> new SkippedDerivation(groups.get(draft.groupId()), disciplines.get(draft.disciplineId()),
                        "Навантаження вже внесено вручну або з плану — його не перезаписано."))
                .distinct()
                .toList();
    }

    private static boolean changes(Draft draft, LoadItem item, int plannedHours) {
        Map<Integer, UUID> current = item.getTeachers().stream().collect(Collectors.toMap(
                link -> link.getSubgroupNumber(), link -> link.getTeacherId()));
        return item.getHoursPerWeek().compareTo(draft.hoursPerWeek()) != 0
                || item.getPlannedHours() != plannedHours
                || item.getLessonKind() != draft.lessonKind()
                || !Objects.equals(current, draft.teachers());
    }

    private static void apply(Draft draft, LoadItem item) {
        item.setLessonKind(draft.lessonKind());
        item.setSplitSubgroups(draft.split());
        item.getTeachers().stream().map(link -> link.getSubgroupNumber())
                .filter(subgroup -> !draft.teachers().containsKey(subgroup))
                .toList()
                .forEach(item::unassignTeacher);
        draft.teachers().forEach(item::assignTeacher);
    }

    private static Key keyOf(LoadItem item) {
        return new Key(item.getGroupId(), item.getDisciplineId(), item.isSplitSubgroups());
    }

    private static final class Counters {
        private int created;
        private int updated;
        private int removed;
    }
}
