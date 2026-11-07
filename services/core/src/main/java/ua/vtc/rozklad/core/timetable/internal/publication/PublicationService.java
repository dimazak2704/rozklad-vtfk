package ua.vtc.rozklad.core.timetable.internal.publication;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.contracts.events.ScheduleChangeReason;
import ua.vtc.rozklad.contracts.events.ScheduleChanged;
import ua.vtc.rozklad.contracts.events.TimetableVersionPublished;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.sse.SseBroadcaster;
import ua.vtc.rozklad.core.shared.sse.SseEventTypes;
import ua.vtc.rozklad.core.timetable.TemplateLesson;
import ua.vtc.rozklad.core.timetable.TemplateLessonRepository;
import ua.vtc.rozklad.core.timetable.TimetableVersion;
import ua.vtc.rozklad.core.timetable.TimetableVersionRepository;
import ua.vtc.rozklad.core.timetable.internal.support.AfterCommit;
import ua.vtc.rozklad.core.timetable.internal.support.UkrainianText;
import ua.vtc.rozklad.core.timetable.internal.version.VersionAnalysis;
import ua.vtc.rozklad.core.timetable.internal.version.VersionLookup;

/**
 * Publication of a draft (DOMAIN.md §6): no hard conflicts, the previous published version is
 * archived, dated lessons from {@code effectiveFrom} to the end of the semester are replaced,
 * lessons of applied changes are kept and their new conflicts reported. Emits
 * {@code timetable.version.published} and {@code schedule.changed} through the outbox.
 */
@Service
public class PublicationService {

    public static final String EFFECTIVE_FROM_OUTSIDE_SEMESTER = "EFFECTIVE_FROM_OUTSIDE_SEMESTER";
    public static final String EFFECTIVE_FROM_IN_PAST = "EFFECTIVE_FROM_IN_PAST";

    private final TimetableVersionRepository versions;
    private final TemplateLessonRepository templateLessons;
    private final SemesterRepository semesters;
    private final VersionLookup lookup;
    private final VersionAnalysis analysis;
    private final LessonMaterializer materializer;
    private final ApplicationEventPublisher events;
    private final SseBroadcaster sse;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public PublicationService(TimetableVersionRepository versions, TemplateLessonRepository templateLessons,
                              SemesterRepository semesters, VersionLookup lookup, VersionAnalysis analysis,
                              LessonMaterializer materializer, ApplicationEventPublisher events, SseBroadcaster sse,
                              JsonMapper jsonMapper, Clock clock) {
        this.versions = versions;
        this.templateLessons = templateLessons;
        this.semesters = semesters;
        this.lookup = lookup;
        this.analysis = analysis;
        this.materializer = materializer;
        this.events = events;
        this.sse = sse;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional
    public PublicationResult publish(UUID versionId, LocalDate effectiveFrom, String actor) {
        UUID semesterId = lookup.require(versionId).getSemesterId();
        // спершу семестр, потім рядок версії: однаковий порядок блокувань в усіх публікаціях
        versions.lockPublication(semesterId);
        TimetableVersion version = lookup.requireDraftForUpdate(versionId);
        Semester semester = semesters.findById(semesterId)
                .orElseThrow(() -> NotFoundException.of("Семестр", semesterId));
        Optional<TimetableVersion> previous = versions.findPublished(semesterId);
        validateEffectiveFrom(semester, effectiveFrom, previous.isPresent());

        List<TemplateLesson> template = templateLessons.findAllByVersionId(versionId);
        VersionAnalysis.Result result = analysis.analyze(semesterId, template);
        if (result.conflicts().hasHard()) {
            throw new ConflictException("Версію не можна опублікувати: у ній є жорсткі конфлікти",
                    result.conflicts().hard());
        }
        List<TemplateLesson> previousLessons = previous.map(old -> templateLessons.findAllByVersionId(old.getId()))
                .orElse(List.of());
        previous.ifPresent(old -> {
            old.markArchived();
            // частковий унікальний індекс «одна опублікована версія» не відкладений: архівуємо до публікації
            versions.saveAndFlush(old);
        });

        LessonMaterializer.Outcome outcome = materializer.materialize(version, semester, template, result.facts(),
                effectiveFrom);
        version.markPublished(effectiveFrom, clock.instant(), actor);
        version.setMetrics(jsonMapper.valueToTree(result.metrics()));
        announce(version, effectiveFrom, outcome.dates(), template, previousLessons);
        return new PublicationResult(version.getId(), effectiveFrom, outcome.materialized(), outcome.kept(),
                outcome.coveredByChanges(), outcome.conflicts());
    }

    private void validateEffectiveFrom(Semester semester, LocalDate effectiveFrom, boolean replacesPublished) {
        if (!semester.contains(effectiveFrom)) {
            throw new BusinessRuleException(EFFECTIVE_FROM_OUTSIDE_SEMESTER,
                    "Дата набрання чинності %s поза семестром «%s» (%s–%s).".formatted(
                            UkrainianText.date(effectiveFrom), semester.getName(),
                            UkrainianText.date(semester.getStartsOn()), UkrainianText.date(semester.getEndsOn())));
        }
        LocalDate today = LocalDate.now(clock);
        // минулі заняття вже відбулися й рахуються в обліку годин: переписувати їх не можна
        if (replacesPublished && effectiveFrom.isBefore(today)) {
            throw new BusinessRuleException(EFFECTIVE_FROM_IN_PAST,
                    "Опублікований розклад не можна змінити заднім числом: оберіть дату не раніше %s."
                            .formatted(UkrainianText.date(today)));
        }
    }

    private void announce(TimetableVersion version, LocalDate effectiveFrom, List<LocalDate> dates,
                          List<TemplateLesson> template, List<TemplateLesson> previousLessons) {
        List<UUID> groupIds = Stream.concat(template.stream(), previousLessons.stream())
                .map(TemplateLesson::getGroupId).distinct().sorted().toList();
        List<UUID> teacherIds = Stream.concat(template.stream(), previousLessons.stream())
                .map(TemplateLesson::getTeacherId).filter(Objects::nonNull).distinct().sorted().toList();
        events.publishEvent(new TimetableVersionPublished(EventEnvelope.create(clock), version.getId(),
                version.getSemesterId(), effectiveFrom, version.getName()));
        ScheduleChanged changed = new ScheduleChanged(EventEnvelope.create(clock), dates, groupIds, teacherIds,
                ScheduleChangeReason.VERSION_PUBLISHED);
        events.publishEvent(changed);
        ScheduleChangedNotice notice = ScheduleChangedNotice.of(changed);
        AfterCommit.run(() -> sse.broadcast(SseEventTypes.SCHEDULE_CHANGED, notice));
    }
}
