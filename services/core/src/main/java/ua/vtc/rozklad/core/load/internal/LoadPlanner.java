package ua.vtc.rozklad.core.load.internal;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.calendar.CalendarQueries;
import ua.vtc.rozklad.core.calendar.GroupScope;
import ua.vtc.rozklad.core.calendar.GroupTheory;
import ua.vtc.rozklad.core.calendar.StudyCalendar;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.load.LoadItem;
import ua.vtc.rozklad.core.load.LoadItemRepository;
import ua.vtc.rozklad.core.load.PlanSource;
import ua.vtc.rozklad.core.load.internal.LoadPlan.Slot;
import ua.vtc.rozklad.core.load.internal.TemplateLessonReader.PlanSlotRow;

/**
 * Counts the AUTO plans of load items ({@link LoadPlan}) from the semester's calendar (the groups' theory days) and
 * the published timetable (the items' weekly lessons), and recounts the stored AUTO plans when either changes. FIXED
 * plans are never touched. A context reads a constant number of queries whatever the number of groups: the study
 * calendar of the semester, the groups' courses and the timetable's slots.
 */
@Component
class LoadPlanner {

    private static final Logger log = LoggerFactory.getLogger(LoadPlanner.class);

    private final CalendarQueries calendar;
    private final CatalogQueries catalog;
    private final TemplateLessonReader lessons;
    private final LoadItemRepository items;

    LoadPlanner(CalendarQueries calendar, CatalogQueries catalog, TemplateLessonReader lessons,
                LoadItemRepository items) {
        this.calendar = calendar;
        this.catalog = catalog;
        this.lessons = lessons;
        this.items = items;
    }

    /** What the plans of {@code groupIds} in the semester are counted from, with the published timetable. */
    Context context(UUID semesterId, Collection<UUID> groupIds) {
        return new Context(semesterId, calendar.studyCalendar(semesterId), catalog.groupRefs(groupIds),
                lessons.publishedSlots(semesterId, groupIds));
    }

    /**
     * Recounts the AUTO plans of the semester's items of {@code groupIds} (all groups when {@code null}). Takes the
     * semester's load lock, so a load write waits for the calendar edit or publication that runs this and then reads
     * the new calendar. {@code versionId}: count with that version's slots (the one being published) instead of the
     * published one; {@code studyCalendar}: a calendar the caller already loaded after its edit.
     *
     * @return items whose plan changed
     */
    @Transactional
    int recount(UUID semesterId, Collection<UUID> groupIds, UUID versionId, StudyCalendar studyCalendar) {
        items.lockSemesterLoad(semesterId);
        List<LoadItem> loaded = groupIds == null ? items.findAllBySemesterId(semesterId)
                : groupIds.isEmpty() ? List.of() : items.findAllBySemesterIdAndGroupIdIn(semesterId, groupIds);
        List<LoadItem> auto = loaded.stream().filter(item -> item.getPlanSource() == PlanSource.AUTO).toList();
        if (auto.isEmpty()) {
            return 0;
        }
        List<UUID> groups = auto.stream().map(LoadItem::getGroupId).distinct().toList();
        StudyCalendar semesterCalendar = studyCalendar != null
                && studyCalendar.semesters().stream().anyMatch(s -> s.semesterId().equals(semesterId))
                ? studyCalendar : calendar.studyCalendar(semesterId);
        List<PlanSlotRow> slots = versionId == null ? lessons.publishedSlots(semesterId, groups)
                : lessons.versionSlots(versionId);
        Context context = new Context(semesterId, semesterCalendar, catalog.groupRefs(groups), slots);
        int changed = 0;
        for (LoadItem item : auto) {
            int plan = context.plannedHours(item, loaded);
            if (plan != item.getPlannedHours()) {
                item.autoPlan(plan);
                changed++;
            }
        }
        if (changed > 0) {
            log.info("Load plans recounted in semester {}: {} of {} AUTO items changed", semesterId, changed, auto.size());
        }
        return changed;
    }

    /** The calendar and timetable facts of one semester for a set of groups. */
    static final class Context {

        private final UUID semesterId;
        private final StudyCalendar studyCalendar;
        private final Map<UUID, GroupRef> groups;
        private final Map<List<UUID>, List<PlanSlotRow>> slots;
        private final Map<UUID, GroupTheory> theories = new HashMap<>();

        Context(UUID semesterId, StudyCalendar studyCalendar, Map<UUID, GroupRef> groups, List<PlanSlotRow> slots) {
            this.semesterId = semesterId;
            this.studyCalendar = studyCalendar;
            this.groups = groups;
            this.slots = slots.stream().collect(Collectors.groupingBy(row -> List.of(row.groupId(), row.disciplineId())));
        }

        /**
         * AUTO plan of {@code item}. {@code siblings}: other stored items of the semester (any group; those of the
         * same group × discipline × split decide whether the timetable's unlinked lessons are unambiguously this item's).
         */
        int plannedHours(LoadItem item, Collection<LoadItem> siblings) {
            return plannedHours(item, item.getHoursPerWeek(), siblings);
        }

        /** The AUTO plan {@code item} would have with {@code hoursPerWeek} (an import compares before it writes). */
        int plannedHours(LoadItem item, BigDecimal hoursPerWeek, Collection<LoadItem> siblings) {
            GroupTheory theory = theories.computeIfAbsent(item.getGroupId(), this::theory);
            return LoadPlan.plannedHours(hoursPerWeek, theory, slotsOf(item, siblings));
        }

        private GroupTheory theory(UUID groupId) {
            GroupRef group = groups.get(groupId);
            // група без курсу в каталозі (видалена) — лише періоди «усі» й «групи»
            return studyCalendar.theory(semesterId, new GroupScope(groupId, group == null ? 0 : group.course()));
        }

        /**
         * The item's weekly lessons: those linked to it (generated versions), plus unlinked ones (imported versions) of
         * the same group × discipline × split — all of them when no other item shares that key, otherwise only those of
         * the item's lesson kind.
         */
        private List<Slot> slotsOf(LoadItem item, Collection<LoadItem> siblings) {
            boolean shared = siblings.stream().anyMatch(other -> !other.getId().equals(item.getId())
                    && other.getGroupId().equals(item.getGroupId())
                    && other.getDisciplineId().equals(item.getDisciplineId())
                    && other.isSplitSubgroups() == item.isSplitSubgroups());
            return slots.getOrDefault(List.of(item.getGroupId(), item.getDisciplineId()), List.of()).stream()
                    .filter(row -> (row.subgroup() > 0) == item.isSplitSubgroups())
                    .filter(row -> item.getId().equals(row.loadItemId())
                            || (row.loadItemId() == null && (!shared || row.lessonKind() == item.getLessonKind())))
                    .map(row -> new Slot(row.subgroup(), row.weekday(), row.parity()))
                    .toList();
        }
    }
}
