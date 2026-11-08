package ua.vtc.rozklad.core.accounting.internal;

import java.time.LocalDate;
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
import ua.vtc.rozklad.core.settings.SettingsService;

/**
 * Plan / fact / forecast per group × discipline (DOMAIN §9). The plan is the sum of planned hours
 * of the load items; lessons are the SCHEDULED dated lessons of the semester. Disciplines with
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
        return compute(calendar.semester(semesterId), groupId).stream()
                .collect(Collectors.toMap(AccountingRow::disciplineId, AccountingRow::progress));
    }

    @Override
    public int hoursPerPair() {
        return settings.hoursPerPair();
    }

    private List<AccountingRow> compute(SemesterInfo semester, UUID groupId) {
        List<LoadItem> items = groupId == null
                ? loadItems.findAllBySemesterId(semester.id())
                : loadItems.findAllBySemesterIdAndGroupId(semester.id(), groupId);
        Map<Key, Integer> plans = new LinkedHashMap<>();
        items.forEach(item -> plans.merge(new Key(item.getGroupId(), item.getDisciplineId()), item.getPlannedHours(),
                Integer::sum));

        Map<Key, List<ScheduledLessonFact>> lessonsByKey = lessons.scheduled(semester.id(), groupId).stream()
                .collect(Collectors.groupingBy(fact -> new Key(fact.groupId(), fact.disciplineId())));
        BiPredicate<LocalDate, Integer> ended = calendar.endedLessons();
        int hoursPerPair = settings.hoursPerPair();

        return plans.entrySet().stream()
                .map(entry -> {
                    List<LedgerLesson> ledgerLessons = lessonsByKey.getOrDefault(entry.getKey(), List.of()).stream()
                            .map(fact -> new LedgerLesson(fact.lessonId(), fact.date(), fact.pair(), fact.subgroup(),
                                    ended.test(fact.date(), fact.pair())))
                            .toList();
                    LedgerResult ledger = HoursLedger.compute(entry.getValue(), hoursPerPair, ledgerLessons);
                    return new AccountingRow(entry.getKey().groupId(), entry.getKey().disciplineId(), ledger);
                })
                .toList();
    }

    private record Key(UUID groupId, UUID disciplineId) {
    }
}
