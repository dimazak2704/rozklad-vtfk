package ua.vtc.rozklad.core.timetable.internal.generation;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.contracts.events.GenerationCancelRequested;
import ua.vtc.rozklad.contracts.events.GenerationRequested;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.core.calendar.SemesterRepository;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.StudentGroupRepository;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.security.Roles;
import ua.vtc.rozklad.core.shared.sse.SseBroadcaster;
import ua.vtc.rozklad.core.shared.sse.SseEventTypes;
import ua.vtc.rozklad.core.timetable.GenerationJob;
import ua.vtc.rozklad.core.timetable.GenerationJobRepository;
import ua.vtc.rozklad.core.timetable.TimetableVersion;
import ua.vtc.rozklad.core.timetable.TimetableVersionRepository;
import ua.vtc.rozklad.core.timetable.internal.support.AfterCommit;
import ua.vtc.rozklad.core.timetable.internal.version.LessonReferenceValidator;

/**
 * Starts, lists and cancels timetable generation. The request goes to the scheduler as
 * {@code generation.requested} through the transactional outbox, so a job is never stored without
 * its request (or the other way round).
 */
@Service
public class GenerationService {

    static final Set<GenerationStatus> ACTIVE = Set.of(GenerationStatus.QUEUED, GenerationStatus.RUNNING);
    static final String EMPTY_SCOPE = "GENERATION_EMPTY_SCOPE";
    static final String BASE_VERSION_MISMATCH = "GENERATION_BASE_VERSION_MISMATCH";

    private final GenerationJobRepository jobs;
    private final SemesterRepository semesters;
    private final StudentGroupRepository groups;
    private final TimetableVersionRepository versions;
    private final ProblemSnapshotBuilder snapshotBuilder;
    private final GenerationJobMapper mapper;
    private final ApplicationEventPublisher events;
    private final SseBroadcaster sse;
    private final Clock clock;

    GenerationService(GenerationJobRepository jobs, SemesterRepository semesters, StudentGroupRepository groups,
                      TimetableVersionRepository versions, ProblemSnapshotBuilder snapshotBuilder,
                      GenerationJobMapper mapper, ApplicationEventPublisher events, SseBroadcaster sse, Clock clock) {
        this.jobs = jobs;
        this.semesters = semesters;
        this.groups = groups;
        this.versions = versions;
        this.snapshotBuilder = snapshotBuilder;
        this.mapper = mapper;
        this.events = events;
        this.sse = sse;
        this.clock = clock;
    }

    @Transactional
    public GenerationJobDto start(GenerationRequestCommand command, String actor) {
        if (!semesters.existsById(command.semesterId())) {
            throw new BusinessRuleException(LessonReferenceValidator.REFERENCE_NOT_FOUND,
                    NotFoundException.of("Семестр", command.semesterId()).getMessage());
        }
        if (jobs.existsBySemesterIdAndStatusIn(command.semesterId(), ACTIVE)) {
            throw activeJobConflict();
        }
        TimetableVersion base = baseVersion(command);
        List<StudentGroup> scope = scope(command);
        ProblemSnapshotBuilder.Built built = snapshotBuilder.build(command.semesterId(), scope, base);
        GenerationWeights weights = command.weights() == null ? GenerationWeights.DEFAULT : command.weights();

        GenerationJob job = new GenerationJob(command.semesterId(), params(command, scope, base, weights), actor);
        job.setBaseVersionId(base == null ? null : base.getId());
        job.setPlan(mapper.json(built.plan()));
        try {
            jobs.saveAndFlush(job);
        } catch (DataIntegrityViolationException concurrentStart) {
            // частковий унікальний індекс: дві генерації семестру стартували одночасно
            throw activeJobConflict();
        }
        events.publishEvent(new GenerationRequested(EventEnvelope.create(clock), job.getId(), command.semesterId(),
                command.timeLimitSeconds(), weights, built.snapshot()));
        return mapper.toDto(job);
    }

    @Transactional(readOnly = true)
    public List<GenerationJobDto> list(UUID semesterId) {
        List<GenerationJob> found = semesterId == null ? jobs.findTop50ByOrderByCreatedAtDesc()
                : jobs.findAllBySemesterIdOrderByCreatedAtDesc(semesterId);
        return found.stream().map(mapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public GenerationJobDto get(UUID jobId) {
        return mapper.toDto(jobs.findById(jobId).orElseThrow(() -> NotFoundException.of("Задачу генерації", jobId)));
    }

    /**
     * Cancels a queued or running job at once (the scheduler is told to stop; its later results are
     * ignored). Cancelling a cancelled job is a no-op; a finished job cannot be cancelled.
     */
    @Transactional
    public GenerationJobDto cancel(UUID jobId) {
        GenerationJob job = jobs.findByIdForUpdate(jobId)
                .orElseThrow(() -> NotFoundException.of("Задачу генерації", jobId));
        if (job.getStatus() == GenerationStatus.CANCELLED) {
            return mapper.toDto(job);
        }
        if (!job.isActive()) {
            throw new ConflictException("Генерацію вже завершено: скасувати її не можна.");
        }
        job.finish(GenerationStatus.CANCELLED, clock.instant(), null);
        events.publishEvent(new GenerationCancelRequested(EventEnvelope.create(clock), jobId));
        GenerationJobDto dto = mapper.toDto(job);
        AfterCommit.run(() -> sse.sendToRole(Roles.ADMIN, SseEventTypes.GENERATION_COMPLETED, dto));
        return dto;
    }

    private TimetableVersion baseVersion(GenerationRequestCommand command) {
        if (command.baseVersionId() == null) {
            return versions.findPublished(command.semesterId()).orElse(null);
        }
        TimetableVersion base = versions.findById(command.baseVersionId()).orElseThrow(() -> new BusinessRuleException(
                LessonReferenceValidator.REFERENCE_NOT_FOUND,
                NotFoundException.of("Версію розкладу", command.baseVersionId()).getMessage()));
        if (!base.getSemesterId().equals(command.semesterId())) {
            throw new BusinessRuleException(BASE_VERSION_MISMATCH, "Базова версія належить іншому семестру.");
        }
        return base;
    }

    /** Groups to generate: the listed ones plus active groups of the listed courses; all active by default. */
    private List<StudentGroup> scope(GenerationRequestCommand command) {
        Map<UUID, StudentGroup> scope = new LinkedHashMap<>();
        if (!command.groupIds().isEmpty()) {
            List<StudentGroup> listed = groups.findAllById(command.groupIds());
            if (listed.size() != Set.copyOf(command.groupIds()).size()) {
                Set<UUID> found = listed.stream().map(StudentGroup::getId).collect(Collectors.toSet());
                UUID missing = command.groupIds().stream().filter(id -> !found.contains(id)).findFirst().orElseThrow();
                throw new BusinessRuleException(LessonReferenceValidator.REFERENCE_NOT_FOUND,
                        NotFoundException.of("Групу", missing).getMessage());
            }
            listed.forEach(group -> scope.put(group.getId(), group));
        }
        List<StudentGroup> active = groups.findAllByActiveTrueOrderByCourseAscCodeAsc();
        if (!command.courses().isEmpty()) {
            active.stream().filter(group -> command.courses().contains(group.getCourse()))
                    .forEach(group -> scope.putIfAbsent(group.getId(), group));
        }
        if (command.groupIds().isEmpty() && command.courses().isEmpty()) {
            active.forEach(group -> scope.put(group.getId(), group));
        }
        if (scope.isEmpty()) {
            throw new BusinessRuleException(EMPTY_SCOPE, "Немає груп для генерації: перевірте вибрані групи й курси.");
        }
        return new ArrayList<>(scope.values());
    }

    private ObjectNode params(GenerationRequestCommand command, Collection<StudentGroup> scope, TimetableVersion base,
                              GenerationWeights weights) {
        ObjectNode params = (ObjectNode) mapper.json(Map.of(
                "timeLimitSeconds", command.timeLimitSeconds(),
                "weights", weights));
        params.set("scope", mapper.json(Map.of("groupIds", command.groupIds(), "courses", command.courses())));
        params.set("groupIds", mapper.json(scope.stream().map(StudentGroup::getId).toList()));
        if (base != null) {
            params.put("baseVersionId", base.getId().toString());
        }
        return params;
    }

    private static ConflictException activeJobConflict() {
        return new ConflictException("Для цього семестру вже виконується генерація: дочекайтеся її або скасуйте.");
    }
}
