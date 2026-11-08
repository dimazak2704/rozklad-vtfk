package ua.vtc.rozklad.core.load.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.GroupInfo;
import ua.vtc.rozklad.core.load.LoadItem;
import ua.vtc.rozklad.core.load.LoadItemRepository;
import ua.vtc.rozklad.core.load.LoadItemTeacher;
import ua.vtc.rozklad.core.load.LoadItemView;
import ua.vtc.rozklad.core.load.LoadPattern;
import ua.vtc.rozklad.core.load.LoadQueries;
import ua.vtc.rozklad.core.load.WeeklyUnit;
import ua.vtc.rozklad.core.settings.SettingsService;

/** Read API of the load module (see {@link LoadQueries}). */
@Service
@Transactional(readOnly = true)
class LoadQueryService implements LoadQueries {

    private final LoadItemRepository items;
    private final CatalogQueries catalog;
    private final SettingsService settings;

    LoadQueryService(LoadItemRepository items, CatalogQueries catalog, SettingsService settings) {
        this.items = items;
        this.catalog = catalog;
        this.settings = settings;
    }

    @Override
    public List<LoadItemView> semesterItems(UUID semesterId) {
        return views(items.findAllBySemesterId(semesterId));
    }

    @Override
    public List<LoadItemView> groupItems(UUID semesterId, UUID groupId) {
        return views(items.findAllBySemesterIdAndGroupId(semesterId, groupId));
    }

    @Override
    public Optional<LoadItemView> item(UUID id) {
        return items.findWithTeachersById(id).map(item -> view(item, settings.hoursPerPair()));
    }

    @Override
    public List<WeeklyUnit> weeklyUnits(UUID semesterId) {
        List<LoadItemView> views = semesterItems(semesterId);
        Map<UUID, GroupInfo> groups = catalog.groups(views.stream().map(LoadItemView::groupId)
                .collect(Collectors.toSet()));
        List<WeeklyUnit> units = new ArrayList<>();
        for (LoadItemView item : views) {
            GroupInfo group = groups.get(item.groupId());
            List<Integer> cohorts = !item.splitSubgroups() ? List.of(0)
                    : group == null ? List.of() : group.subgroups();
            for (int subgroup : cohorts) {
                UUID teacherId = item.teachers().get(subgroup);
                int index = 0;
                for (int whole = 0; whole < item.pattern().whole(); whole++) {
                    units.add(unit(item, subgroup, teacherId, UnitWeight.WHOLE, index++));
                }
                for (int half = 0; half < item.pattern().half(); half++) {
                    units.add(unit(item, subgroup, teacherId, UnitWeight.HALF, index++));
                }
            }
        }
        return units;
    }

    @Override
    public LoadPattern pattern(BigDecimal hoursPerWeek) {
        return LoadPattern.of(hoursPerWeek, settings.hoursPerPair());
    }

    @Override
    public boolean teachesInSemester(UUID teacherId, UUID disciplineId, UUID semesterId) {
        return items.teacherHasDiscipline(teacherId, disciplineId, semesterId);
    }

    private List<LoadItemView> views(Collection<LoadItem> found) {
        int hoursPerPair = settings.hoursPerPair();
        return found.stream().map(item -> view(item, hoursPerPair)).toList();
    }

    private static LoadItemView view(LoadItem item, int hoursPerPair) {
        Map<Integer, UUID> teachers = item.getTeachers().stream()
                .collect(Collectors.toMap(LoadItemTeacher::getSubgroupNumber, LoadItemTeacher::getTeacherId));
        return new LoadItemView(item.getId(), item.getSemesterId(), item.getGroupId(), item.getDisciplineId(),
                item.getLessonKind(), item.getHoursPerWeek(), item.getPlannedHours(), item.isSplitSubgroups(), teachers,
                item.getRequiredRoomType(), item.getPreferredRoomId(), item.getControlForm(), item.getStreamId(),
                item.getSource(), LoadPattern.of(item.getHoursPerWeek(), hoursPerPair));
    }

    private static WeeklyUnit unit(LoadItemView item, int subgroup, UUID teacherId, UnitWeight weight, int index) {
        return new WeeklyUnit(item.id() + ":" + subgroup + ":" + index, item.id(), item.groupId(), subgroup,
                item.disciplineId(), teacherId, weight, item.requiredRoomType(), item.preferredRoomId(), item.streamId());
    }
}
