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
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.events.GenerationCompleted;
import ua.vtc.rozklad.contracts.events.GenerationProgress;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.DisciplineRepository;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.RoomRepository;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.shared.messaging.ProcessedEvents;
import ua.vtc.rozklad.core.shared.security.Roles;
import ua.vtc.rozklad.core.shared.sse.SseAfterCommit;
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
import ua.vtc.rozklad.core.timetable.internal.support.LoadReadRepository;
import ua.vtc.rozklad.core.timetable.internal.version.VersionAnalysis;

/**
 * Applies scheduler results to jobs, idempotently: every event id is processed once (duplicates
 * from redelivery are ignored), events of finished or unknown jobs are ignored. A SOLVED result
 * becomes a DRAFT version (source GENERATED) with metrics «було / стало». Realtime updates go to
 * admins over SSE after commit. The catalog and the load may change during a run: a room, teacher
 * or load item deleted meanwhile is left out of its lessons (with a note), a deleted group or
 * discipline fails the job, so the result never breaks a foreign key and is never lost silently.
 */
@Component
class GenerationResultHandler {

    private static final Logger log = LoggerFactory.getLogger(GenerationResultHandler.class);
    private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(TimeConfig.KYIV);
    private static final int MAX_ERROR_LENGTH = 2000;
    static final String REFERENCES_DELETED = "Під час генерації з каталогу видалили групу або дисципліну, для якої "
            + "складався розклад. Запустіть генерацію ще раз.";

    private final ProcessedEvents processedEvents;
    private final GenerationJobRepository jobs;
    private final TimetableVersionRepository versions;
    private final TemplateLessonRepository templateLessons;
    private final RoomRepository rooms;
    private final TeacherRepository teachers;
    private final StudentGroupRepository groups;
    private final DisciplineRepository disciplines;
    private final LoadReadRepository loadItems;
    private final VersionAnalysis analysis;
    private final GenerationJobMapper mapper;
    private final SseAfterCommit sse;
    private final Clock clock;

    GenerationResultHandler(ProcessedEvents processedEvents, GenerationJobRepository jobs,
                            TimetableVersionRepository versions, TemplateLessonRepository templateLessons,
                            RoomRepository rooms, TeacherRepository teachers, StudentGroupRepository groups,
                            DisciplineRepository disciplines, LoadReadRepository loadItems, VersionAnalysis analysis,
                            GenerationJobMapper mapper, SseAfterCommit sse, Clock clock) {
        this.processedEvents = processedEvents;
        this.jobs = jobs;
        this.versions = versions;
        this.templateLessons = templateLessons;
        this.rooms = rooms;
        this.teachers = teachers;
        this.groups = groups;
        this.disciplines = disciplines;
        this.loadItems = loadItems;
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
        sse.sendToRole(Roles.ADMIN, SseEventTypes.GENERATION_PROGRESS, notice);
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
        sse.sendToRole(Roles.ADMIN, SseEventTypes.GENERATION_COMPLETED, dto);
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
            sse.sendToRole(Roles.ADMIN, SseEventTypes.GENERATION_COMPLETED, dto);
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
        Known known = known(result);
        Set<UUID> missingGroups = missing(result, PlanUnit::groupId, known.groups());
        Set<UUID> missingDisciplines = missing(result, PlanUnit::disciplineId, known.disciplines());
        if (!missingGroups.isEmpty() || !missingDisciplines.isEmpty()) {
            log.warn("Generation job {}: groups {} and disciplines {} were deleted during the run", job.getId(),
                    missingGroups, missingDisciplines);
            job.finish(GenerationStatus.FAILED, now, REFERENCES_DELETED);
            return;
        }
        TimetableVersion version = new TimetableVersion(job.getSemesterId(), "Згенеровано " + NAME_TIME.format(now),
                VersionSource.GENERATED);
        version.setGenerationJobId(job.getId());
        version.setNotes(notes(result, known));
        versions.save(version);
        List<TemplateLesson> lessons = result.placements().stream()
                .map(placement -> toLesson(version.getId(), placement, known))
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

    /**
     * Which referenced rows still exist. Load items are share-locked, so a delete that starts now waits
     * for this transaction instead of breaking its insert.
     */
    private Known known(GeneratedLessonsBuilder.Result result) {
        List<PlanUnit> units = result.placements().stream().map(GeneratedLessonsBuilder.Placement::unit).toList();
        Set<UUID> roomIds = result.placements().stream()
                .map(GenerationResultHandler::roomOf).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> loadItemIds = ids(units, PlanUnit::loadItemId);
        return new Known(
                rooms.findAllById(roomIds).stream().map(Room::getId).collect(Collectors.toSet()),
                teachers.findAllById(ids(units, PlanUnit::teacherId)).stream().map(Teacher::getId)
                        .collect(Collectors.toSet()),
                groups.findAllById(ids(units, PlanUnit::groupId)).stream().map(StudentGroup::getId)
                        .collect(Collectors.toSet()),
                disciplines.findAllById(ids(units, PlanUnit::disciplineId)).stream().map(Discipline::getId)
                        .collect(Collectors.toSet()),
                loadItems.lockExisting(loadItemIds));
    }

    private static Set<UUID> ids(List<PlanUnit> units, Function<PlanUnit, UUID> id) {
        return units.stream().map(id).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static Set<UUID> missing(GeneratedLessonsBuilder.Result result, Function<PlanUnit, UUID> id,
                                     Set<UUID> known) {
        return result.placements().stream().map(placement -> id.apply(placement.unit()))
                .filter(value -> value != null && !known.contains(value))
                .collect(Collectors.toSet());
    }

    /** The room a lesson gets: a stream follower was sent without one and takes the stream's room from the base. */
    private static UUID roomOf(GeneratedLessonsBuilder.Placement placement) {
        PlanUnit unit = placement.unit();
        return placement.roomId() == null && unit.streamId() != null && unit.pinned() != null
                ? unit.pinned().roomId() : placement.roomId();
    }

    private static TemplateLesson toLesson(UUID versionId, GeneratedLessonsBuilder.Placement placement, Known known) {
        PlanUnit unit = placement.unit();
        TemplateLesson lesson = new TemplateLesson(versionId, placement.weekday(), placement.pair(), placement.parity(),
                unit.groupId(), unit.subgroup(), unit.disciplineId());
        // рядок, видалений під час генерації, не потрапляє в заняття (про це пише примітка версії)
        lesson.setTeacherId(existing(unit.teacherId(), known.teachers()));
        // учасник потоку надсилався без аудиторії (PlanUnit.toStreamFollowerUnit): аудиторія потоку з бази
        UUID room = roomOf(placement);
        lesson.setRoomId(existing(room, known.rooms()));
        lesson.setLoadItemId(existing(unit.loadItemId(), known.loadItems()));
        lesson.setLessonKind(unit.lessonKind());
        lesson.setStreamId(unit.streamId());
        lesson.setOnline(unit.online());
        lesson.setPinned(unit.pinned() != null);
        return lesson;
    }

    private static UUID existing(UUID id, Set<UUID> known) {
        return id != null && known.contains(id) ? id : null;
    }

    private static String notes(GeneratedLessonsBuilder.Result result, Known known) {
        List<String> notes = new ArrayList<>();
        if (!result.unplaced().isEmpty()) {
            notes.add("Не розміщено занять: " + result.unplaced().size() + ".");
        }
        long unknownRooms = result.placements().stream()
                .filter(placement -> roomOf(placement) != null && !known.rooms().contains(roomOf(placement)))
                .count();
        if (unknownRooms > 0) {
            notes.add("Аудиторій, яких уже немає в каталозі (заняття без аудиторії): " + unknownRooms + ".");
        }
        long unknownTeachers = result.placements().stream().map(placement -> placement.unit().teacherId())
                .filter(teacher -> teacher != null && !known.teachers().contains(teacher))
                .count();
        if (unknownTeachers > 0) {
            notes.add("Викладачів, яких уже немає в каталозі (заняття без викладача): " + unknownTeachers + ".");
        }
        long unknownLoad = result.placements().stream().map(placement -> placement.unit().loadItemId())
                .filter(item -> item != null && !known.loadItems().contains(item))
                .count();
        if (unknownLoad > 0) {
            notes.add("Занять, чиє навантаження видалили під час генерації (без зв'язку з навантаженням): "
                    + unknownLoad + ".");
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

    /** Ids of the referenced rows that still exist when the result is applied. */
    private record Known(Set<UUID> rooms, Set<UUID> teachers, Set<UUID> groups, Set<UUID> disciplines,
                         Set<UUID> loadItems) {
    }

    /** SSE data of {@code generation.progress}. */
    record GenerationProgressNotice(UUID jobId, long elapsedMs, long hardScore, long softScore) {
    }
}
