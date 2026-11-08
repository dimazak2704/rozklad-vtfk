package ua.vtc.rozklad.core.timetable.internal.publication;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.contracts.events.ScheduleChangeReason;
import ua.vtc.rozklad.contracts.events.ScheduleChanged;
import ua.vtc.rozklad.core.calendar.CalendarQueries;
import ua.vtc.rozklad.core.calendar.StudyCalendar;
import ua.vtc.rozklad.core.calendar.StudySemester;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.sse.SseAfterCommit;
import ua.vtc.rozklad.core.shared.sse.SseEventTypes;
import ua.vtc.rozklad.core.timetable.DatedLessonRefresh;
import ua.vtc.rozklad.core.timetable.LessonRepository;
import ua.vtc.rozklad.core.timetable.TemplateLesson;
import ua.vtc.rozklad.core.timetable.TemplateLessonRepository;
import ua.vtc.rozklad.core.timetable.TimetableVersion;
import ua.vtc.rozklad.core.timetable.TimetableVersionRepository;
import ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFacts;
import ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFactsLoader;
import ua.vtc.rozklad.core.timetable.internal.version.VersionAnalysis;

/**
 * Re-materializes some group-days with the same planner as a publication, then reconciles: a
 * template lesson that is still planned keeps its row (only its online flag and room occupancy may
 * change), the rest is deleted and the missing lessons are inserted. The version of a date is the
 * one published last among those effective by that date, so a date before a newer version's
 * {@code effectiveFrom} still follows the older one. Dates before today and semesters without a
 * published version are left alone.
 */
@Component
@Transactional
class LessonRefresher implements DatedLessonRefresh {

    private final LessonMaterializationRepository repository;
    private final LessonRepository lessons;
    private final TimetableVersionRepository versions;
    private final TemplateLessonRepository templateLessons;
    private final CalendarQueries calendar;
    private final ConflictFactsLoader factsLoader;
    private final ApplicationEventPublisher events;
    private final SseAfterCommit sse;
    private final EntityManager entityManager;
    private final Clock clock;
    private final MaterializationPlanner planner = new MaterializationPlanner();

    LessonRefresher(LessonMaterializationRepository repository, LessonRepository lessons,
                    TimetableVersionRepository versions, TemplateLessonRepository templateLessons,
                    CalendarQueries calendar, ConflictFactsLoader factsLoader, ApplicationEventPublisher events,
                    SseAfterCommit sse, EntityManager entityManager, Clock clock) {
        this.repository = repository;
        this.lessons = lessons;
        this.versions = versions;
        this.templateLessons = templateLessons;
        this.calendar = calendar;
        this.factsLoader = factsLoader;
        this.events = events;
        this.sse = sse;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Override
    public Result refresh(Collection<LocalDate> dates, Collection<UUID> groupIds) {
        Result result = reconcile(dates, groupIds);
        if (result.changedAnything()) {
            ScheduleChanged changed = new ScheduleChanged(EventEnvelope.create(clock),
                    dates.stream().distinct().sorted().toList(), groupIds.stream().distinct().sorted().toList(),
                    List.of(), ScheduleChangeReason.LESSONS_EDITED);
            events.publishEvent(changed);
            sse.broadcast(SseEventTypes.SCHEDULE_CHANGED, ScheduleChangedNotice.of(changed));
        }
        return result;
    }

    /** The work of {@link #refresh} without an announcement (the calendar already announced its edit). */
    Result reconcile(Collection<LocalDate> dates, Collection<UUID> groupIds) {
        LocalDate today = LocalDate.now(clock);
        SortedSet<LocalDate> days = dates.stream().filter(date -> !date.isBefore(today))
                .collect(Collectors.toCollection(TreeSet::new));
        Set<UUID> groups = new HashSet<>(groupIds);
        if (days.isEmpty() || groups.isEmpty()) {
            return Result.NONE;
        }
        // зміни поточної транзакції (період календаря, статус зміни) мають бути в БД до SQL нижче
        entityManager.flush();
        StudyCalendar study = calendar.studyCalendar(days.first(), days.last());
        // той самий порядок, що й у публікації: семестр, потім заняття
        study.semesters().stream().map(StudySemester::semesterId).sorted().forEach(versions::lockPublication);
        repository.lockGroupDays(days, groups);

        List<ReplaceableLesson> replaceable = repository.replaceable(days, groups);
        Set<UUID> replaceableIds = replaceable.stream().map(ReplaceableLesson::id).collect(Collectors.toSet());
        Map<LocalDate, List<KeptLesson>> keptByDate = repository.lessonsOn(days).stream()
                .filter(lesson -> !replaceableIds.contains(lesson.id()))
                .collect(Collectors.groupingBy(KeptLesson::date));

        Set<LocalDate> untouched = new HashSet<>();
        List<NewLesson> planned = new ArrayList<>();
        List<ConflictDto> clashes = new ArrayList<>();
        Map<UUID, VersionTemplate> templates = new HashMap<>();
        for (LocalDate day : days) {
            Optional<StudySemester> semester = study.semesterOf(day);
            if (semester.isEmpty()) {
                continue;
            }
            Optional<TimetableVersion> version = governingVersion(semester.get().semesterId(), day);
            if (version.isEmpty()) {
                untouched.add(day);
                continue;
            }
            VersionTemplate template = templates.computeIfAbsent(version.get().getId(),
                    id -> template(semester.get().semesterId(), id, groups));
            MaterializationPlanner.Plan plan = planner.plan(new MaterializationPlanner.Input(
                    semester.get().semesterId(), version.get().getId(), template.lessons(), study, template.facts(),
                    keptByDate.getOrDefault(day, List.of()), day, day));
            planned.addAll(plan.lessons());
            clashes.addAll(plan.clashes());
        }

        Map<PlanKey, NewLesson> missing = new LinkedHashMap<>();
        planned.forEach(lesson -> missing.put(new PlanKey(lesson.templateLessonId(), lesson.date()), lesson));
        List<UUID> removed = new ArrayList<>();
        List<ReplaceableLesson> updated = new ArrayList<>();
        for (ReplaceableLesson existing : replaceable) {
            if (untouched.contains(existing.date())) {
                continue;
            }
            NewLesson match = existing.scheduled() && existing.templateLessonId() != null
                    ? missing.remove(new PlanKey(existing.templateLessonId(), existing.date())) : null;
            if (match == null) {
                removed.add(existing.id());
            } else if (match.online() != existing.online() || match.roomExclusive() != existing.roomExclusive()) {
                updated.add(existing.withMode(match.online(), match.roomExclusive()));
            }
        }
        repository.delete(removed);
        repository.updateMode(updated, clock.instant());
        repository.insert(List.copyOf(missing.values()), clock.instant());
        // збій унікальності слотів тут, а не під час commit: план мав його виключити
        lessons.checkSlotConstraintsNow();
        return new Result(missing.size(), removed.size(), updated.size(), Set.copyOf(removed), clashes);
    }

    /** The version published last among those effective by {@code date}. */
    private Optional<TimetableVersion> governingVersion(UUID semesterId, LocalDate date) {
        return versions.findAllBySemesterIdOrderByCreatedAtDesc(semesterId).stream()
                .filter(version -> version.getPublishedAt() != null && version.getEffectiveFrom() != null)
                .filter(version -> !version.getEffectiveFrom().isAfter(date))
                .max(Comparator.comparing(TimetableVersion::getPublishedAt));
    }

    private VersionTemplate template(UUID semesterId, UUID versionId, Set<UUID> groups) {
        List<TemplateLesson> lessonsOfGroups = templateLessons.findAllByVersionId(versionId).stream()
                .filter(lesson -> groups.contains(lesson.getGroupId()))
                .toList();
        ConflictFacts facts = factsLoader.load(lessonsOfGroups.stream()
                .map(lesson -> VersionAnalysis.slotLesson(lesson, null)).toList(), semesterId);
        return new VersionTemplate(lessonsOfGroups, facts);
    }

    private record VersionTemplate(List<TemplateLesson> lessons, ConflictFacts facts) {
    }

    private record PlanKey(UUID templateLessonId, LocalDate date) {
    }
}
