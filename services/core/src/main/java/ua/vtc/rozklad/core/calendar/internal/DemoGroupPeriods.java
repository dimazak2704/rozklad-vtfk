package ua.vtc.rozklad.core.calendar.internal;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.calendar.CalendarPeriod;
import ua.vtc.rozklad.core.calendar.CalendarPeriodKind;
import ua.vtc.rozklad.core.calendar.CalendarPeriodRepository;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.calendar.StudyMode;
import ua.vtc.rozklad.core.catalog.CatalogImported;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.GroupInfo;
import ua.vtc.rozklad.core.shared.persistence.DemoSeedConfig;

/**
 * Demo seed (DOMAIN §12) of the calendar periods that belong to concrete groups — practice of 2КІ3
 * and distance learning of 4КІ, as in the notes of order №35. The groups come from the Excel
 * import, which happens after Flyway, so the periods are created at startup and after every catalog
 * import once the groups exist. Idempotent: an existing period only gets missing groups added.
 */
@Component
@Profile(DemoSeedConfig.PROFILE)
class DemoGroupPeriods {

    static final String SEMESTER = "I семестр 2026/2027";
    static final List<Definition> PERIODS = List.of(
            new Definition(CalendarPeriodKind.PRACTICE, "Електрорадіомонтажна практика 2КІ3",
                    LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 30), null,
                    "2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р.", List.of("2КІ3")),
            new Definition(CalendarPeriodKind.STUDY_MODE, "4КІ навчаються дистанційно",
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31), StudyMode.ONLINE,
                    "Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно.", List.of("4КІ1", "4КІ2", "4КІ3")));

    private static final Logger log = LoggerFactory.getLogger(DemoGroupPeriods.class);

    private final SemesterRepository semesters;
    private final CalendarPeriodRepository periods;
    private final CatalogQueries catalog;

    DemoGroupPeriods(SemesterRepository semesters, CalendarPeriodRepository periods, CatalogQueries catalog) {
        this.semesters = semesters;
        this.periods = periods;
        this.catalog = catalog;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onStartup() {
        ensurePeriods();
    }

    @ApplicationModuleListener
    public void on(CatalogImported imported) {
        ensurePeriods();
    }

    void ensurePeriods() {
        Optional<Semester> semester = semesters.findAll().stream()
                .filter(candidate -> candidate.getName().equals(SEMESTER))
                .findFirst();
        if (semester.isEmpty()) {
            return;
        }
        List<CalendarPeriod> existing = periods.findAllBySemesterIdOrderByStartsOn(semester.get().getId());
        for (Definition definition : PERIODS) {
            Set<UUID> groupIds = definition.groupCodes().stream()
                    .map(catalog::groupByCode)
                    .flatMap(Optional::stream)
                    .map(GroupInfo::id)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (groupIds.isEmpty()) {
                continue;
            }
            Optional<CalendarPeriod> period = existing.stream()
                    .filter(candidate -> candidate.getTitle().equals(definition.title()))
                    .findFirst();
            if (period.isEmpty()) {
                CalendarPeriod created = new CalendarPeriod(semester.get().getId(), definition.kind(),
                        definition.title(), definition.startsOn(), definition.endsOn());
                created.setStudyMode(definition.studyMode());
                created.setOrderNote(definition.orderNote());
                created.applyToGroups(groupIds);
                periods.save(created);
                log.info("Demo seed: calendar period «{}» created for {} group(s)", definition.title(), groupIds.size());
            } else if (!period.get().getGroupIds().containsAll(groupIds)) {
                Set<UUID> union = new LinkedHashSet<>(period.get().getGroupIds());
                union.addAll(groupIds);
                period.get().applyToGroups(union);
                log.info("Demo seed: calendar period «{}» now covers {} group(s)", definition.title(), union.size());
            }
        }
    }

    record Definition(CalendarPeriodKind kind, String title, LocalDate startsOn, LocalDate endsOn, StudyMode studyMode,
                      String orderNote, List<String> groupCodes) {
    }
}
