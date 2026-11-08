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
import java.util.Objects;
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
import ua.vtc.rozklad.core.calendar.DayInfo;
import ua.vtc.rozklad.core.calendar.GroupScope;
import ua.vtc.rozklad.core.calendar.StudyCalendar;
import ua.vtc.rozklad.core.calendar.StudySemester;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.shared.sse.SseAfterCommit;
import ua.vtc.rozklad.core.shared.sse.SseEventTypes;
import ua.vtc.rozklad.core.shared.text.UkrainianText;
import ua.vtc.rozklad.core.timetable.DatedLessonRefresh;
import ua.vtc.rozklad.core.timetable.LessonRepository;
import ua.vtc.rozklad.core.timetable.TemplateLesson;
import ua.vtc.rozklad.core.timetable.TemplateLessonRepository;
import ua.vtc.rozklad.core.timetable.TimetableVersion;
import ua.vtc.rozklad.core.timetable.TimetableVersionRepository;
import ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFacts;
import ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFactsLoader;
import ua.vtc.rozklad.core.timetable.internal.publication.LessonMaterializationRepository.ModeUpdate;
import ua.vtc.rozklad.core.timetable.internal.version.VersionAnalysis;

/**
 * Re-materializes some group-days with the same planner as a publication, then reconciles: a
 * template lesson that is still planned keeps its row (only its online flag and room occupancy may
 * change), the rest is deleted and the missing lessons are inserted. The version of a date is the
 * one published last among those effective by that date, so a date before a newer version's
 * {@code effectiveFrom} still follows the older one. Lessons the refresh keeps (changed by an order,
 * kept for an open decision) follow the study mode of their day as well. Dates before today and
 * semesters without a published version are left alone.
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
        Result result = reconcile(dates, groupIds, null);
        if (result.changedAnything()) {
            ScheduleChanged changed = new ScheduleChanged(EventEnvelope.create(clock),
                    dates.stream().distinct().sorted().toList(), groupIds.stream().distinct().sorted().toList(),
                    List.of(), ScheduleChangeReason.LESSONS_EDITED);
            events.publishEvent(changed);
            sse.broadcast(SseEventTypes.SCHEDULE_CHANGED, ScheduleChangedNotice.of(changed));
        }
        return result;
    }

    /**
     * The work of {@link #refresh} without an announcement (the calendar already announced its edit).
     *
     * @param before the calendar before a calendar edit: a hard conflict that the planner finds with it
     *               as well (a template lesson that was already missing, a lesson that was already out of
     *               place) is not the edit's doing and is not reported; {@code null} reports every one
     */
    Result reconcile(Collection<LocalDate> dates, Collection<UUID> groupIds, StudyCalendar before) {
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
        List<ModeUpdate> modeUpdates = new ArrayList<>();
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
            List<KeptLesson> keptNow = keptByDate.getOrDefault(day, List.of());
            ModePass modes = followStudyMode(keptNow, groups, study, day);
            modeUpdates.addAll(modes.updates());
            clashes.addAll(modes.clashes());
            MaterializationPlanner.Plan plan = planner.plan(new MaterializationPlanner.Input(
                    semester.get().semesterId(), version.get().getId(), template.lessons(), study, template.facts(),
                    modes.kept(), day, day));
            planned.addAll(plan.lessons());
            Set<ClashKey> earlier = before == null ? Set.of() : clashesBefore(before, day, keptNow, groups, templates);
            plan.found().stream()
                    .filter(clash -> !earlier.contains(new ClashKey(clash.subjectId(), clash.date())))
                    .map(MaterializationPlanner.Clash::conflict)
                    .forEach(clashes::add);
        }

        Map<PlanKey, NewLesson> missing = new LinkedHashMap<>();
        planned.forEach(lesson -> missing.put(new PlanKey(lesson.templateLessonId(), lesson.date()), lesson));
        List<UUID> removed = new ArrayList<>();
        for (ReplaceableLesson existing : replaceable) {
            if (untouched.contains(existing.date())) {
                continue;
            }
            NewLesson match = existing.scheduled() && existing.templateLessonId() != null
                    ? missing.remove(new PlanKey(existing.templateLessonId(), existing.date())) : null;
            if (match == null) {
                removed.add(existing.id());
            } else if (match.online() != existing.online() || match.roomExclusive() != existing.roomExclusive()) {
                modeUpdates.add(new ModeUpdate(existing.id(), match.online(), match.roomExclusive()));
            }
        }
        repository.delete(removed);
        repository.updateMode(modeUpdates, clock.instant());
        repository.insert(List.copyOf(missing.values()), clock.instant());
        // збій унікальності слотів тут, а не під час commit: план мав його виключити
        lessons.checkSlotConstraintsNow();
        return new Result(missing.size(), removed.size(), modeUpdates.size(), Set.copyOf(removed), clashes);
    }

    /**
     * Lessons that a refresh keeps (changed by an order, or kept for an open decision) follow the
     * study mode of their day like template lessons do (DOMAIN §2, §5): online when the template
     * lesson they stand for is online or the group studies online that day. A lesson that becomes
     * offline occupies its room again, so the room must be free.
     */
    private ModePass followStudyMode(List<KeptLesson> kept, Set<UUID> groups, StudyCalendar study, LocalDate day) {
        List<KeptLesson> adjusted = new ArrayList<>(kept.size());
        List<ModeUpdate> updates = new ArrayList<>();
        List<KeptLesson> occupyAgain = new ArrayList<>();
        for (KeptLesson lesson : kept) {
            DayInfo info = lesson.scheduled() && groups.contains(lesson.groupId())
                    ? study.day(day, new GroupScope(lesson.groupId(), lesson.course())) : null;
            // день без занять: такі уроки окремо повідомляє strandedByCalendar
            if (info == null || !info.studyDay()) {
                adjusted.add(lesson);
                continue;
            }
            KeptLesson target = lesson.withMode((lesson.root() != null && lesson.root().online()) || info.online());
            if (target.online() != lesson.online() || target.roomExclusive() != lesson.roomExclusive()) {
                updates.add(new ModeUpdate(lesson.id(), target.online(), target.roomExclusive()));
                if (target.roomExclusive() && !lesson.roomExclusive()) {
                    occupyAgain.add(target);
                }
            }
            adjusted.add(target);
        }
        List<ConflictDto> clashes = new ArrayList<>();
        for (KeptLesson lesson : occupyAgain) {
            Optional<KeptLesson> holder = adjusted.stream()
                    .filter(other -> !other.id().equals(lesson.id()) && other.scheduled() && other.roomExclusive())
                    .filter(other -> other.pair() == lesson.pair() && Objects.equals(other.roomId(), lesson.roomId()))
                    .filter(other -> lesson.roomId() != null && !other.inStream(lesson.streamId()))
                    .findFirst();
            if (holder.isPresent()) {
                clashes.add(roomTakenAgain(lesson, holder.get()));
                // не записуємо: база відхилила б подвійну аудиторію, а правку все одно буде відхилено
                updates.removeIf(update -> update.id().equals(lesson.id()));
            }
        }
        return new ModePass(adjusted, updates, clashes);
    }

    private ConflictDto roomTakenAgain(KeptLesson lesson, KeptLesson other) {
        LessonMaterializationRepository.LessonLabel label = repository.label(lesson.id());
        return ConflictDto.hard(ConflictKind.ROOM, ("%s %s%s, %s, %d пара: заняття, змінене розпорядженням, стає "
                        + "очним, а аудиторія %s у цей час зайнята іншим заняттям. Спершу змініть аудиторію "
                        + "доповненням.").formatted(label.groupCode(), label.disciplineName(),
                        label.teacherName() == null ? "" : " " + label.teacherName(), UkrainianText.date(lesson.date()),
                        lesson.pair(), label.roomName()),
                List.of(lesson.id(), other.id()), lesson.date(), lesson.pair());
    }

    /** Hard conflicts of the day as the planner finds them with the calendar before the edit. */
    private Set<ClashKey> clashesBefore(StudyCalendar before, LocalDate day, List<KeptLesson> keptNow,
                                        Set<UUID> groups, Map<UUID, VersionTemplate> templates) {
        Optional<StudySemester> semester = before.semesterOf(day);
        if (semester.isEmpty()) {
            return Set.of();
        }
        Optional<TimetableVersion> version = governingVersion(semester.get().semesterId(), day);
        if (version.isEmpty()) {
            return Set.of();
        }
        VersionTemplate template = templates.computeIfAbsent(version.get().getId(),
                id -> template(semester.get().semesterId(), id, groups));
        return planner.plan(new MaterializationPlanner.Input(semester.get().semesterId(), version.get().getId(),
                        template.lessons(), before, template.facts(), keptNow, day, day))
                .found().stream()
                .map(clash -> new ClashKey(clash.subjectId(), clash.date()))
                .collect(Collectors.toSet());
    }

    /**
     * Lessons of live changes that a calendar edit would leave on a day their group has no classes
     * (holiday, practice, a day outside the semester): the refresh does not touch them (DOMAIN §6),
     * so the edit must not be made silently. Dates before today are history and are not checked;
     * with {@code before}, a lesson that was already on such a day is not the edit's doing.
     */
    List<ConflictDto> strandedByCalendar(Collection<LocalDate> dates, Collection<UUID> groupIds,
                                         StudyCalendar before) {
        LocalDate today = LocalDate.now(clock);
        SortedSet<LocalDate> days = dates.stream().filter(date -> !date.isBefore(today))
                .collect(Collectors.toCollection(TreeSet::new));
        if (days.isEmpty() || groupIds.isEmpty()) {
            return List.of();
        }
        StudyCalendar study = calendar.studyCalendar(days.first(), days.last());
        List<ConflictDto> conflicts = new ArrayList<>();
        for (StrandedLesson lesson : repository.keptScheduled(days, Set.copyOf(groupIds))) {
            GroupScope scope = new GroupScope(lesson.groupId(), lesson.course());
            if (study.day(lesson.date(), scope).studyDay()
                    || before != null && !before.day(lesson.date(), scope).studyDay()) {
                continue;
            }
            String what = "%s %s%s%s, %s, %d пара".formatted(lesson.groupCode(), lesson.disciplineName(),
                    lesson.teacherName() == null ? "" : " " + lesson.teacherName(),
                    lesson.subgroup() > 0 ? " (підгр. " + lesson.subgroup() + ")" : "",
                    UkrainianText.date(lesson.date()), lesson.pair());
            conflicts.add(ConflictDto.hard(ConflictKind.GROUP, lesson.fromOrder()
                            ? what + ": заняття поставлене опублікованим розпорядженням, а занять у групи цього дня "
                            + "не буде. Спершу скасуйте цю пару доповненням."
                            : what + ": для заняття є відкрите рішення диспетчера, а занять у групи цього дня не буде. "
                            + "Спершу скасуйте це рішення.",
                    List.of(lesson.id()), lesson.date(), lesson.pair()));
        }
        return conflicts;
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

    private record ClashKey(UUID subjectId, LocalDate date) {
    }

    /** Kept lessons in the study mode of their day, the rows to update and rooms that are taken. */
    private record ModePass(List<KeptLesson> kept, List<ModeUpdate> updates, List<ConflictDto> clashes) {
    }
}
