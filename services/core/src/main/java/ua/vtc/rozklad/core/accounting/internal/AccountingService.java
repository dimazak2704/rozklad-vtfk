package ua.vtc.rozklad.core.accounting.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.accounting.AccountingQueries;
import ua.vtc.rozklad.core.accounting.DisciplineProgress;
import ua.vtc.rozklad.core.calendar.SemesterInfo;
import ua.vtc.rozklad.core.load.LoadItem;
import ua.vtc.rozklad.core.load.LoadItemRepository;
import ua.vtc.rozklad.core.load.PlanSource;
import ua.vtc.rozklad.core.settings.SettingsService;

/**
 * Plan / fact / forecast per group × discipline (DOMAIN §9). The plan is the sum of planned hours
 * of the load items (counted by the load module over the group's theory days, owner's rule 15.11.2026, unless a
 * number was set); lessons are the SCHEDULED dated lessons of the semester. Disciplines with
 * lessons but without a load item have no plan and are not reported.
 */
@Service
@Transactional(readOnly = true)
public class AccountingService implements AccountingQueries {

    private final LoadItemRepository loadItems;
    private final AccountingLessons lessons;
    private final AccountingCalendar calendar;
    private final SettingsService settings;

    AccountingService(LoadItemRepository loadItems, AccountingLessons lessons, AccountingCalendar calendar,
                      SettingsService settings) {
        this.loadItems = loadItems;
        this.lessons = lessons;
        this.calendar = calendar;
        this.settings = settings;
    }

    /** Rows of the semester (active one when {@code semesterId} is null), optionally of one group. */
    public List<AccountingRow> rows(UUID semesterId, UUID groupId) {
        return compute(calendar.semester(semesterId), groupId);
    }

    @Override
    public Map<UUID, DisciplineProgress> progressOfGroup(UUID semesterId, UUID groupId) {
        return progress(compute(calendar.semester(semesterId), groupId));
    }

    @Override
    public Map<UUID, Map<UUID, DisciplineProgress>> progressOfGroups(UUID semesterId, Collection<UUID> groupIds) {
        // the semester, the ended-lesson rule and hours per pair are the same for every group: read them once
        SemesterInfo semester = calendar.semester(semesterId);
        BiPredicate<LocalDate, Integer> ended = calendar.endedLessons();
        int hoursPerPair = settings.hoursPerPair();
        List<UUID> ids = groupIds.stream().distinct().toList();
        Map<UUID, List<LoadItem>> itemsByGroup = ids.isEmpty() ? Map.of()
                : loadItems.findAllBySemesterIdAndGroupIdIn(semester.id(), ids).stream()
                        .collect(Collectors.groupingBy(LoadItem::getGroupId));
        Map<UUID, Map<UUID, DisciplineProgress>> result = new LinkedHashMap<>();
        ids.forEach(groupId -> result.put(groupId, progress(compute(semester, groupId,
                itemsByGroup.getOrDefault(groupId, List.of()), ended, hoursPerPair))));
        return result;
    }

    private static Map<UUID, DisciplineProgress> progress(List<AccountingRow> rows) {
        return rows.stream().collect(Collectors.toMap(AccountingRow::disciplineId, AccountingRow::progress));
    }

    @Override
    public int hoursPerPair() {
        return settings.hoursPerPair();
    }

    private List<AccountingRow> compute(SemesterInfo semester, UUID groupId) {
        List<LoadItem> items = groupId == null
                ? loadItems.findAllBySemesterId(semester.id())
                : loadItems.findAllBySemesterIdAndGroupId(semester.id(), groupId);
        return compute(semester, groupId, items, calendar.endedLessons(), settings.hoursPerPair());
    }

    private List<AccountingRow> compute(SemesterInfo semester, UUID groupId, List<LoadItem> items,
                                        BiPredicate<LocalDate, Integer> ended, int hoursPerPair) {
        Map<Key, List<LoadItem>> itemsByKey = items.stream().collect(Collectors.groupingBy(
                item -> new Key(item.getGroupId(), item.getDisciplineId()), LinkedHashMap::new, Collectors.toList()));

        Map<Key, List<ScheduledLessonFact>> lessonsByKey = lessons.scheduled(semester.id(), groupId).stream()
                .collect(Collectors.groupingBy(fact -> new Key(fact.groupId(), fact.disciplineId())));

        return itemsByKey.entrySet().stream()
                .map(entry -> {
                    List<LedgerLesson> ledgerLessons = lessonsByKey.getOrDefault(entry.getKey(), List.of()).stream()
                            .map(fact -> new LedgerLesson(fact.lessonId(), fact.date(), fact.pair(), fact.subgroup(),
                                    ended.test(fact.date(), fact.pair())))
                            .toList();
                    List<LoadItem> keyItems = entry.getValue();
                    int plan = keyItems.stream().mapToInt(LoadItem::getPlannedHours).sum();
                    LedgerResult ledger = HoursLedger.compute(plan, hoursPerPair, ledgerLessons);
                    BigDecimal hoursPerWeek = keyItems.stream().map(LoadItem::getHoursPerWeek)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    return new AccountingRow(entry.getKey().groupId(), entry.getKey().disciplineId(), ledger,
                            hoursPerWeek, planSource(keyItems));
                })
                .toList();
    }

    /** AUTO or FIXED when all items of the discipline agree, MIXED otherwise. */
    private static String planSource(List<LoadItem> items) {
        List<PlanSource> sources = items.stream().map(LoadItem::getPlanSource).distinct().toList();
        return sources.size() == 1 ? sources.getFirst().name() : "MIXED";
    }

    private record Key(UUID groupId, UUID disciplineId) {
    }
}
