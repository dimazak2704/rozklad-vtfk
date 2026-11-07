package ua.vtc.rozklad.core.timetable.internal.generation;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.events.GenerationCompleted;
import ua.vtc.rozklad.contracts.events.GenerationProgress;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.RoomRepository;
import ua.vtc.rozklad.core.shared.messaging.ProcessedEvents;
import ua.vtc.rozklad.core.shared.security.Roles;
import ua.vtc.rozklad.core.shared.sse.SseBroadcaster;
import ua.vtc.rozklad.core.shared.sse.SseEventTypes;
import ua.vtc.rozklad.core.shared.time.TimeConfig;
import ua.vtc.rozklad.core.timetable.GenerationJob;
import ua.vtc.rozklad.core.timetable.GenerationJobRepository;
import ua.vtc.rozklad.core.timetable.TemplateLesson;
import ua.vtc.rozklad.core.timetable.TemplateLessonRepository;
import ua.vtc.rozklad.core.timetable.TimetableVersion;
import ua.vtc.rozklad.core.timetable.TimetableVersionRepository;
import ua.vtc.rozklad.core.timetable.VersionSource;
import ua.vtc.rozklad.core.timetable.internal.metrics.MetricsDto;
import ua.vtc.rozklad.core.timetable.internal.support.AfterCommit;
import ua.vtc.rozklad.core.timetable.internal.version.VersionAnalysis;

/**
 * Applies scheduler results to jobs, idempotently: every event id is processed once (duplicates
 * from redelivery are ignored), events of finished or unknown jobs are ignored. A SOLVED result
 * becomes a DRAFT version (source GENERATED) with metrics «було / стало». Realtime updates go to
 * admins over SSE after commit.
 */
@Component
class GenerationResultHandler {

    private static final Logger log = LoggerFactory.getLogger(GenerationResultHandler.class);
    private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(TimeConfig.KYIV);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final ProcessedEvents processedEvents;
    private final GenerationJobRepository jobs;
    private final TimetableVersionRepository versions;
    private final TemplateLessonRepository templateLessons;
    private final RoomRepository rooms;
    private final VersionAnalysis analysis;
    private final GenerationJobMapper mapper;
    private final SseBroadcaster sse;
    private final Clock clock;

    GenerationResultHandler(ProcessedEvents processedEvents, GenerationJobRepository jobs,
                            TimetableVersionRepository versions, TemplateLessonRepository templateLessons,
                            RoomRepository rooms, VersionAnalysis analysis, GenerationJobMapper mapper,
                            SseBroadcaster sse, Clock clock) {
        this.processedEvents = processedEvents;
        this.jobs = jobs;
        this.versions = versions;
        this.templateLessons = templateLessons;
        this.rooms = rooms;
        this.analysis = analysis;
        this.mapper = mapper;
        this.sse = sse;
        this.clock = clock;
    }

    @Transactional
    public void onProgress(GenerationProgress event) {
        if (!processedEvents.markProcessed(event.eventId())) {
            log.debug("Duplicate generation.progress {} ignored", event.eventId());
            return;
        }
        Optional<GenerationJob> found = activeJob(event.jobId(), "generation.progress");
        if (found.isEmpty()) {
            return;
        }
        GenerationJob job = found.get();
        double seconds = Math.round(event.elapsedMs() / 100.0) / 10.0;
        job.recordProgress(clock.instant(), mapper.withProgressPoint(job.getProgress(), seconds, event.hardScore(),
                event.softScore()), GenerationJobMapper.score(event.hardScore(), event.softScore()));
        GenerationProgressNotice notice = new GenerationProgressNotice(job.getId(), event.elapsedMs(),
                event.hardScore(), event.softScore());
        AfterCommit.run(() -> sse.sendToRole(Roles.ADMIN, SseEventTypes.GENERATION_PROGRESS, notice));
    }

    @Transactional
    public void onCompleted(GenerationCompleted event) {
        if (!processedEvents.markProcessed(event.eventId())) {
            log.debug("Duplicate generation.completed {} ignored", event.eventId());
            return;
        }
        Optional<GenerationJob> found = activeJob(event.jobId(), "generation.completed");
        if (found.isEmpty()) {
            return;
        }
        GenerationJob job = found.get();
        job.setBestScore(GenerationJobMapper.score(event.hardScore(), event.softScore()));
        job.setAnalysis(mapper.json(event.analysis()));
        Instant now = clock.instant();
        switch (event.status()) {
            case SOLVED -> applySolution(job, event, now);
            case FAILED -> job.finish(GenerationStatus.FAILED, now, truncate(failure(event)));
            case CANCELLED -> job.finish(GenerationStatus.CANCELLED, now, null);
            default -> throw new IllegalStateException("Not a terminal status: " + event.status());
        }
        GenerationJobDto dto = mapper.toDto(job);
        AfterCommit.run(() -> sse.sendToRole(Roles.ADMIN, SseEventTypes.GENERATION_COMPLETED, dto));
    }

    /**
     * Fails a job the scheduler went silent on. The job is checked again under the row lock, so a
     * progress event that arrived meanwhile keeps it alive.
     */
    @Transactional
    public Optional<GenerationJobDto> failIfStale(UUID jobId, Predicate<GenerationJob> stale, Instant now,
                                                  String reason) {
        Optional<GenerationJob> found = jobs.findByIdForUpdate(jobId).filter(GenerationJob::isActive).filter(stale);
        found.ifPresent(job -> {
            job.finish(GenerationStatus.FAILED, now, reason);
            GenerationJobDto dto = mapper.toDto(job);
            AfterCommit.run(() -> sse.sendToRole(Roles.ADMIN, SseEventTypes.GENERATION_COMPLETED, dto));
        });
        return found.map(mapper::toDto);
    }

    private void applySolution(GenerationJob job, GenerationCompleted event, Instant now) {
        GeneratedLessonsBuilder.Result result;
        try {
            result = GeneratedLessonsBuilder.build(mapper.plan(job), event.assignments());
        } catch (GeneratedLessonsBuilder.InvalidResultException invalid) {
            log.warn("Generation job {} returned an invalid result: {}", job.getId(), invalid.getMessage());
            job.finish(GenerationStatus.FAILED, now,
                    truncate("Планувальник повернув некоректний результат: " + invalid.getMessage()));
            return;
        }
        Set<UUID> knownRooms = rooms.findAllById(result.placements().stream()
                        .map(GeneratedLessonsBuilder.Placement::roomId).filter(Objects::nonNull)
                        .collect(Collectors.toSet()))
                .stream().map(Room::getId).collect(Collectors.toSet());
        TimetableVersion version = new TimetableVersion(job.getSemesterId(), "Згенеровано " + NAME_TIME.format(now),
                VersionSource.GENERATED);
        version.setGenerationJobId(job.getId());
        version.setNotes(notes(result, knownRooms));
        versions.save(version);
        List<TemplateLesson> lessons = result.placements().stream()
                .map(placement -> toLesson(version.getId(), placement, knownRooms))
                .toList();
        templateLessons.saveAll(lessons);

        MetricsDto after = analysis.analyze(job.getSemesterId(), lessons).metrics();
        MetricsDto before = Optional.ofNullable(job.getBaseVersionId())
                .flatMap(versions::findById)
                .map(base -> analysis.analyze(base.getSemesterId(), templateLessons.findAllByVersionId(base.getId()))
                        .metrics())
                .orElse(null);
        version.setMetrics(mapper.json(after));
        job.setResultVersionId(version.getId());
        job.setMetricsAfter(mapper.json(after));
        job.setMetricsBefore(mapper.json(before));
        job.finish(GenerationStatus.SOLVED, now, null);
    }

    private static TemplateLesson toLesson(UUID versionId, GeneratedLessonsBuilder.Placement placement,
                                           Set<UUID> knownRooms) {
        PlanUnit unit = placement.unit();
        TemplateLesson lesson = new TemplateLesson(versionId, placement.weekday(), placement.pair(), placement.parity(),
                unit.groupId(), unit.subgroup(), unit.disciplineId());
        lesson.setTeacherId(unit.teacherId());
        lesson.setRoomId(placement.roomId() != null && knownRooms.contains(placement.roomId()) ? placement.roomId()
                : null);
        lesson.setLoadItemId(unit.loadItemId());
        lesson.setLessonKind(unit.lessonKind());
        lesson.setStreamId(unit.streamId());
        lesson.setOnline(unit.online());
        lesson.setPinned(unit.pinned() != null);
        return lesson;
    }

    private static String notes(GeneratedLessonsBuilder.Result result, Set<UUID> knownRooms) {
        List<String> notes = new ArrayList<>();
        if (!result.unplaced().isEmpty()) {
            notes.add("Не розміщено занять: " + result.unplaced().size() + ".");
        }
        long unknownRooms = result.placements().stream()
                .filter(placement -> placement.roomId() != null && !knownRooms.contains(placement.roomId()))
                .count();
        if (unknownRooms > 0) {
            notes.add("Аудиторій, яких уже немає в каталозі (заняття без аудиторії): " + unknownRooms + ".");
        }
        return notes.isEmpty() ? null : String.join(" ", notes);
    }

    private Optional<GenerationJob> activeJob(UUID jobId, String eventName) {
        Optional<GenerationJob> job = jobs.findByIdForUpdate(jobId);
        if (job.isEmpty()) {
            log.warn("{} for unknown generation job {} ignored", eventName, jobId);
            return Optional.empty();
        }
        if (!job.get().isActive()) {
            log.info("{} for generation job {} in status {} ignored", eventName, jobId, job.get().getStatus());
            return Optional.empty();
        }
        return job;
    }

    private static String failure(GenerationCompleted event) {
        return event.error() == null || event.error().isBlank() ? "Планувальник не зміг скласти розклад."
                : event.error();
    }

    private static String truncate(String text) {
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }

    /** SSE data of {@code generation.progress}. */
    record GenerationProgressNotice(UUID jobId, long elapsedMs, long hardScore, long softScore) {
    }
}
