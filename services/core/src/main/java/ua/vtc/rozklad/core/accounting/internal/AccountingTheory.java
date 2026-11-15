package ua.vtc.rozklad.core.accounting.internal;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.calendar.CalendarQueries;
import ua.vtc.rozklad.core.calendar.GroupScope;
import ua.vtc.rozklad.core.calendar.GroupTheory;
import ua.vtc.rozklad.core.calendar.SemesterInfo;
import ua.vtc.rozklad.core.calendar.StudyCalendar;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.UkrainianOrder;
import ua.vtc.rozklad.core.load.LoadItemRepository;

/**
 * Why the plans are what they are: the theory weeks of every group with load in the semester (owner's rule
 * 15.11.2026, counted from the system's calendar) next to the number of the college's academic calendar, which is
 * only a cross-check (its semester may be longer, 4th course to 14.02). A constant number of queries for any number
 * of groups: the semester, the group ids, the groups, the study calendar and the graph's rows.
 */
@Service
@Transactional(readOnly = true)
public class AccountingTheory {

    private final AccountingCalendar semesters;
    private final CalendarQueries calendar;
    private final CatalogQueries catalog;
    private final LoadItemRepository loadItems;

    AccountingTheory(AccountingCalendar semesters, CalendarQueries calendar, CatalogQueries catalog,
                     LoadItemRepository loadItems) {
        this.semesters = semesters;
        this.calendar = calendar;
        this.catalog = catalog;
        this.loadItems = loadItems;
    }

    /** Groups of the semester (active one when {@code semesterId} is null) that have load, or the one group. */
    public List<GroupTheoryRow> groups(UUID semesterId, UUID groupId) {
        SemesterInfo semester = semesters.semester(semesterId);
        List<UUID> ids = groupId != null ? List.of(groupId) : loadItems.findGroupIds(semester.id());
        Map<UUID, GroupRef> groups = catalog.groupRefs(ids);
        if (groups.isEmpty()) {
            return List.of();
        }
        StudyCalendar studyCalendar = calendar.studyCalendar(semester.id());
        Map<UUID, Integer> graph = calendar.theoryWeeksFromGraph(semester.id());
        return groups.values().stream()
                .sorted(Comparator.comparingInt(GroupRef::course).thenComparing(GroupRef::code, UkrainianOrder.NATURAL))
                .map(group -> new GroupTheoryRow(group,
                        studyCalendar.theory(semester.id(), new GroupScope(group.id(), group.course())),
                        graph.get(group.id())))
                .toList();
    }

    /** @param graphTheoryWeeks theory weeks of the college's academic calendar, {@code null} when not imported */
    public record GroupTheoryRow(GroupRef group, GroupTheory theory, Integer graphTheoryWeeks) {
    }
}
