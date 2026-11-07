package ua.vtc.rozklad.scheduler.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.contracts.events.GenerationCancelRequested;
import ua.vtc.rozklad.contracts.events.GenerationCompleted;
import ua.vtc.rozklad.contracts.events.GenerationProgress;
import ua.vtc.rozklad.contracts.events.GenerationRequested;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.contracts.events.RozkladEvent;
import ua.vtc.rozklad.contracts.events.RozkladEvents;
import ua.vtc.rozklad.scheduler.config.KeycloakRealmRoleConverter;
import ua.vtc.rozklad.scheduler.solver.score.TimetableConstraint;
import ua.vtc.rozklad.scheduler.support.SchedulerIntegrationTest;
import ua.vtc.rozklad.scheduler.support.SyntheticCollege;

/**
 * The whole flow over RabbitMQ: {@code generation.requested} and {@code generation.cancel} in,
 * {@code generation.progress} and {@code generation.completed} out, with the job table in
 * PostgreSQL and the read API.
 */
@SchedulerIntegrationTest
class GenerationMessagingIT {

    private static final UUID SEMESTER = UUID.fromString("5e000000-0000-4000-8000-000000000001");
    private static final Duration SOLVE_TIMEOUT = Duration.ofSeconds(90);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GenerationJobRepository jobs;

    @Autowired
    private JobMaintenance maintenance;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private Clock clock;

    private final List<Object> received = new ArrayList<>();
    private String resultsQueue;

    @BeforeEach
    void listenToResults() {
        // не exclusive/auto-delete (черга зникла б після першого receive), а RabbitMQ 4 не дозволяє
        // тимчасових неексклюзивних черг — тож durable, і видаляємо її після тесту
        Queue queue = new Queue("it.generation-results." + UUID.randomUUID(), true, false, false);
        amqpAdmin.declareQueue(queue);
        TopicExchange exchange = new TopicExchange(RozkladEvents.EXCHANGE);
        amqpAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(RozkladEvents.GENERATION_PROGRESS));
        amqpAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(RozkladEvents.GENERATION_COMPLETED));
        resultsQueue = queue.getName();
    }

    @AfterEach
    void removeResultsQueue() {
        amqpAdmin.deleteQueue(resultsQueue);
    }

    @Test
    void requestIsSolvedWithProgressAndAnalysis() throws Exception {
        SyntheticCollege.Problem college = SyntheticCollege.generate(SyntheticCollege.Options.small());
        UUID jobId = UUID.randomUUID();

        send(request(jobId, 4, college.snapshot()));

        GenerationCompleted completed = awaitCompletion(jobId);
        assertThat(completed.status()).isEqualTo(GenerationStatus.SOLVED);
        assertThat(completed.hardScore()).isZero();
        assertThat(completed.error()).isNull();
        assertThat(completed.assignments()).hasSameSizeAs(college.snapshot().units())
                .extracting(GenerationCompleted.Assignment::unitId)
                .containsExactlyElementsOf(college.snapshot().units().stream().map(ProblemSnapshot.Unit::unitId)
                        .toList());
        assertThat(completed.analysis()).hasSize(TimetableConstraint.values().length)
                .first().satisfies(row -> {
                    assertThat(row.constraint()).isEqualTo("Накладка у викладача");
                    assertThat(row.matchCount()).isZero();
                    assertThat(row.score()).isEqualTo("0hard/0soft");
                });
        assertThat(eventsOf(jobId, GenerationProgress.class)).isNotEmpty()
                .allSatisfy(progress -> assertThat(progress.hardScore()).isZero());

        mockMvc.perform(admin(get("/api/scheduler/jobs/{id}", jobId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SOLVED"))
                .andExpect(jsonPath("$.semesterId").value(SEMESTER.toString()))
                .andExpect(jsonPath("$.unitCount").value(college.snapshot().units().size()))
                .andExpect(jsonPath("$.hardScore").value(0))
                .andExpect(jsonPath("$.bestScore").value(startsWithZeroHard()))
                .andExpect(jsonPath("$.resultDelivered").value(true));
    }

    @Test
    void redeliveredEventsAndRepeatedJobIdsRunTheJobOnce() {
        SyntheticCollege.Problem college = SyntheticCollege.generate(SyntheticCollege.Options.tiny());
        UUID jobId = UUID.randomUUID();
        GenerationRequested request = request(jobId, 2, college.snapshot());

        send(request);
        send(request);
        send(request(jobId, 2, college.snapshot()));

        assertThat(awaitCompletion(jobId).status()).isEqualTo(GenerationStatus.SOLVED);
        assertThat(nextCompletion(jobId, Duration.ofSeconds(4))).isNull();
    }

    @Test
    void cancelStopsARunningJobAndKeepsItsBestTimetable() {
        SyntheticCollege.Problem college = SyntheticCollege.generate(SyntheticCollege.Options.medium());
        UUID jobId = UUID.randomUUID();
        send(request(jobId, 300, college.snapshot()));
        awaitEvent(jobId, GenerationProgress.class);

        send(new GenerationCancelRequested(EventEnvelope.create(clock), jobId));

        GenerationCompleted completed = awaitCompletion(jobId);
        assertThat(completed.status()).isEqualTo(GenerationStatus.CANCELLED);
        assertThat(completed.assignments()).hasSameSizeAs(college.snapshot().units());
        assertThat(completed.error()).isNull();
        GenerationJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.isCancelRequested()).isTrue();
        assertThat(job.getFinishedAt()).isBefore(job.getStartedAt().plusSeconds(60));
    }

    @Test
    void cancelBeforeTheRequestWinsOverTheLateRequest() throws Exception {
        UUID jobId = UUID.randomUUID();

        send(new GenerationCancelRequested(EventEnvelope.create(clock), jobId));
        GenerationCompleted cancelled = awaitCompletion(jobId);
        send(request(jobId, 2, SyntheticCollege.generate(SyntheticCollege.Options.tiny()).snapshot()));

        assertThat(cancelled.status()).isEqualTo(GenerationStatus.CANCELLED);
        assertThat(cancelled.assignments()).isEmpty();
        assertThat(nextCompletion(jobId, Duration.ofSeconds(3))).isNull();
        mockMvc.perform(admin(get("/api/scheduler/jobs/{id}", jobId)))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.semesterId").doesNotExist());
    }

    @Test
    void inconsistentSnapshotFailsWithUkrainianExplanation() {
        ProblemSnapshot valid = SyntheticCollege.generate(SyntheticCollege.Options.tiny()).snapshot();
        UUID unknownGroup = UUID.randomUUID();
        List<ProblemSnapshot.Unit> units = new ArrayList<>(valid.units());
        ProblemSnapshot.Unit first = units.getFirst();
        units.set(0, new ProblemSnapshot.Unit(first.unitId(), first.loadItemId(), unknownGroup, 0,
                first.disciplineId(), first.teacherId(), first.weight(), null, null, null, null));
        UUID jobId = UUID.randomUUID();

        send(request(jobId, 5, new ProblemSnapshot(valid.timeslots(), valid.shiftWindows(), valid.groups(),
                valid.rooms(), valid.teachers(), units)));

        GenerationCompleted completed = awaitCompletion(jobId);
        assertThat(completed.status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(completed.assignments()).isEmpty();
        assertThat(completed.error()).startsWith("Некоректні вхідні дані генерації.")
                .contains("Заняття " + first.unitId() + " посилається на невідому групу " + unknownGroup);
    }

    @Test
    void timeLimitAboveTheMaximumIsRejected() {
        UUID jobId = UUID.randomUUID();

        send(request(jobId, 3_600, SyntheticCollege.generate(SyntheticCollege.Options.tiny()).snapshot()));

        GenerationCompleted completed = awaitCompletion(jobId);
        assertThat(completed.status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(completed.error()).isEqualTo("Ліміт часу 3600 с перевищує дозволений максимум 600 с.");
    }

    @Test
    void jobsInterruptedByARestartFailAndAreAnnounced() {
        UUID jobId = UUID.randomUUID();
        GenerationJob interrupted = GenerationJob.requested(jobId, SEMESTER, 60, 10,
                jsonMapper.valueToTree(GenerationWeights.DEFAULT), Instant.now(clock).minus(Duration.ofHours(1)));
        interrupted.start(Instant.now(clock).minus(Duration.ofMinutes(59)));
        jobs.save(interrupted);

        maintenance.recoverInterruptedJobs();

        GenerationCompleted completed = awaitCompletion(jobId);
        assertThat(completed.status()).isEqualTo(GenerationStatus.FAILED);
        assertThat(completed.error()).isEqualTo(GenerationLifecycle.INTERRUPTED);
        assertThat(jobs.findById(jobId).orElseThrow().getStatus()).isEqualTo(GenerationStatus.FAILED);
    }

    @Test
    void completionNotConfirmedByTheBrokerIsSentAgainWithTheSameEventId() {
        UUID jobId = UUID.randomUUID();
        GenerationCompleted original = new GenerationCompleted(EventEnvelope.create(clock), jobId,
                GenerationStatus.FAILED, 0, 0, List.of(), List.of(), "Помилка для перевірки повторної відправки.");
        GenerationJob job = GenerationJob.requested(jobId, SEMESTER, 60, 10,
                jsonMapper.valueToTree(GenerationWeights.DEFAULT), Instant.now(clock).minus(Duration.ofMinutes(5)));
        job.finish(GenerationStatus.FAILED, null, null, original.error(), jsonMapper.valueToTree(original),
                Instant.now(clock).minus(Duration.ofMinutes(4)));
        jobs.save(job);

        maintenance.resendPendingCompletions();

        GenerationCompleted resent = awaitCompletion(jobId);
        assertThat(resent.eventId()).isEqualTo(original.eventId());
        assertThat(resent.error()).isEqualTo(original.error());
        assertThat(jobs.findById(jobId).orElseThrow().getCompletionSentAt()).isNotNull();
    }

    @Test
    void unreadableMessageEndsInTheDeadLetterQueue() {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setHeader("__TypeId__", GenerationRequested.class.getName());
        Message broken = MessageBuilder.withBody("{\"jobId\": ".getBytes(StandardCharsets.UTF_8))
                .andProperties(properties).build();

        rabbitTemplate.send(RozkladEvents.EXCHANGE, RozkladEvents.GENERATION_REQUESTED, broken);

        Message dead = rabbitTemplate.receive(
                RozkladEvents.Queues.deadLetterQueue(RozkladEvents.Queues.SCHEDULER_GENERATION), 20_000);
        assertThat(dead).isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo("{\"jobId\": ");
    }

    @Test
    void unknownJobIsNotFoundAndStatusCountsActiveJobs() throws Exception {
        mockMvc.perform(admin(get("/api/scheduler/jobs/{id}", UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Не знайдено"))
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:not-found"));
        mockMvc.perform(admin(get("/api/scheduler/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queuedJobs").isNumber())
                .andExpect(jsonPath("$.runningJobs").isNumber());
    }

    // ---- helpers

    private GenerationRequested request(UUID jobId, int timeLimitSeconds, ProblemSnapshot snapshot) {
        return new GenerationRequested(EventEnvelope.create(clock), jobId, SEMESTER, timeLimitSeconds,
                GenerationWeights.DEFAULT, snapshot);
    }

    private void send(RozkladEvent event) {
        rabbitTemplate.convertAndSend(RozkladEvents.EXCHANGE, RozkladEvents.routingKey(event), event);
    }

    private GenerationCompleted awaitCompletion(UUID jobId) {
        return awaitEvent(jobId, GenerationCompleted.class);
    }

    /** Next completion of the job within {@code timeout}, or {@code null}. */
    private GenerationCompleted nextCompletion(UUID jobId, Duration timeout) {
        return receive(jobId, GenerationCompleted.class, timeout);
    }

    private <T extends RozkladEvent> T awaitEvent(UUID jobId, Class<T> type) {
        T event = receive(jobId, type, SOLVE_TIMEOUT);
        if (event == null) {
            throw new AssertionError("No " + type.getSimpleName() + " for job " + jobId + " within " + SOLVE_TIMEOUT);
        }
        return event;
    }

    private <T extends RozkladEvent> T receive(UUID jobId, Class<T> type, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Object message = rabbitTemplate.receiveAndConvert(resultsQueue, 250);
            if (message != null) {
                received.add(message);
                if (type.isInstance(message) && jobId.equals(jobIdOf(message))) {
                    return type.cast(message);
                }
            }
        }
        return null;
    }

    private <T> List<T> eventsOf(UUID jobId, Class<T> type) {
        return received.stream().filter(type::isInstance).filter(event -> jobId.equals(jobIdOf(event)))
                .map(type::cast).toList();
    }

    private static UUID jobIdOf(Object event) {
        return switch (event) {
            case GenerationProgress progress -> progress.jobId();
            case GenerationCompleted completed -> completed.jobId();
            default -> null;
        };
    }

    private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.with(jwt()
                .jwt(token -> token.claim("realm_access", Map.of("roles", List.of("ADMIN"))))
                .authorities(new KeycloakRealmRoleConverter()));
    }

    private static org.hamcrest.Matcher<String> startsWithZeroHard() {
        return org.hamcrest.Matchers.startsWith("0hard/");
    }
}
