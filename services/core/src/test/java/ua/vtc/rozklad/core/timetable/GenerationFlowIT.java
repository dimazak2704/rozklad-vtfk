package ua.vtc.rozklad.core.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.contracts.events.GenerationCancelRequested;
import ua.vtc.rozklad.contracts.events.GenerationCompleted;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.Assignment;
import ua.vtc.rozklad.contracts.events.GenerationProgress;
import ua.vtc.rozklad.contracts.events.GenerationRequested;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.contracts.events.RozkladEvents;
import ua.vtc.rozklad.core.load.LoadItem;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.substitution.PreferenceKind;
import ua.vtc.rozklad.core.substitution.TeacherPreference;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.timetable.internal.generation.GenerationJobDto;
import ua.vtc.rozklad.core.timetable.internal.generation.GenerationRequestCommand;
import ua.vtc.rozklad.core.timetable.internal.generation.GenerationService;
import ua.vtc.rozklad.core.timetable.internal.importing.ImportTimetableCommand;
import ua.vtc.rozklad.core.timetable.internal.importing.TimetableImportService;

/**
 * Generation orchestration over RabbitMQ: the problem snapshot leaves through the outbox, progress
 * and results come back on {@code core.q.scheduler-results}, a solved result becomes a draft, a
 * redelivered result is ignored, a cancelled job ignores late results.
 */
@CoreIntegrationTest
class GenerationFlowIT {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private EntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private GenerationService generation;

    @Autowired
    private TimetableImportService imports;

    @Autowired
    private GenerationJobRepository jobs;

    @Autowired
    private TimetableVersionRepository versions;

    @Autowired
    private TemplateLessonRepository templateLessons;

    private TimetableItData data;
    private UUID baseVersionId;

    @BeforeEach
    void loadAndBaseVersion() {
        data = new TimetableItData(LocalDate.now(clock)).create(transaction, em);
        transaction.executeWithoutResult(status -> {
            UUID semesterId = data.semester.getId();
            // 3 год/тиждень ⇒ одна ціла пара й половинка (DOMAIN §4)
            LoadItem stp = new LoadItem(semesterId, data.group4ki1.getId(), data.stp.getId(), BigDecimal.valueOf(3),
                    66);
            stp.assignTeacher(0, data.krentsin.getId());
            em.persist(stp);
            LoadItem km = new LoadItem(semesterId, data.group4ki1.getId(), data.km.getId(), BigDecimal.valueOf(2), 44);
            km.assignTeacher(0, data.nepyivoda.getId());
            em.persist(km);
            em.persist(new TeacherPreference(data.krentsin.getId(), semesterId, 1, 1, PreferenceKind.UNAVAILABLE,
                    null));
        });
        // базова версія: 4КІ2 поза генерацією лишається як є, КМ групи 4КІ1 закріплено диспетчером
        baseVersionId = imports.importTimetable(new ImportTimetableCommand(data.semester.getId(), "База", List.of(
                new ImportTimetableCommand.Lesson(2, 1, Parity.BOTH, "4КІ2", 0, "НДЕКСМ", "Непийвода М.В.", "304",
                        false),
                new ImportTimetableCommand.Lesson(4, 2, Parity.BOTH, "4КІ1", 0, "КМ", "Непийвода М.В.", "6",
                        false)))).versionId();
        transaction.executeWithoutResult(status -> em.createQuery("""
                        update TemplateLesson t set t.pinned = true
                        where t.versionId = :v and t.groupId = :g
                        """)
                .setParameter("v", baseVersionId).setParameter("g", data.group4ki1.getId()).executeUpdate());
    }

    @AfterEach
    void cleanUp() {
        TimetableItData.cleanUp(jdbc);
    }

    @Test
    void solvedResultBecomesAGeneratedDraftAndARedeliveryIsIgnored() {
        Queue requests = probe(RozkladEvents.GENERATION_REQUESTED);

        GenerationJobDto job = generation.start(command(), "admin");

        assertThat(job.status()).isEqualTo(GenerationStatus.QUEUED);
        GenerationRequested request = (GenerationRequested) rabbitTemplate.receiveAndConvert(requests.getName(),
                15_000);
        assertThat(request).isNotNull();
        assertThat(request.jobId()).isEqualTo(job.id());
        assertThat(request.timeLimitSeconds()).isEqualTo(30);
        ProblemSnapshot problem = request.problem();
        assertThat(problem.timeslots()).hasSize(38);
        assertThat(problem.shiftWindows()).hasSize(10);
        assertThat(problem.groups()).extracting(ProblemSnapshot.Group::code).containsExactly("4КІ1", "4КІ2");
        assertThat(problem.teachers()).filteredOn(teacher -> teacher.id().equals(data.krentsin.getId()))
                .singleElement()
                .satisfies(teacher -> assertThat(teacher.unavailable()).containsExactly(new ProblemSnapshot.Timeslot(1, 1)));
        Map<String, ProblemSnapshot.Unit> units = problem.units().stream()
                .collect(Collectors.toMap(ProblemSnapshot.Unit::unitId, Function.identity()));
        assertThat(units.values()).extracting(ProblemSnapshot.Unit::weight)
                .containsExactlyInAnyOrder(UnitWeight.WHOLE, UnitWeight.HALF, UnitWeight.WHOLE, UnitWeight.WHOLE);
        ProblemSnapshot.Unit pinnedKm = units.values().stream()
                .filter(unit -> unit.disciplineId().equals(data.km.getId())).findFirst().orElseThrow();
        assertThat(pinnedKm.pinned()).isEqualTo(new ProblemSnapshot.PinnedSlot(4, 2, Parity.BOTH, data.lab6.getId()));
        ProblemSnapshot.Unit outsideScope = units.values().stream()
                .filter(unit -> unit.groupId().equals(data.group4ki2.getId())).findFirst().orElseThrow();
        assertThat(outsideScope.pinned()).isEqualTo(new ProblemSnapshot.PinnedSlot(2, 1, Parity.BOTH,
                data.room304.getId()));
        assertThatThrownBy(() -> generation.start(command(), "admin")).isInstanceOf(ConflictException.class);

        send(RozkladEvents.GENERATION_PROGRESS, new GenerationProgress(EventEnvelope.create(clock), job.id(), 2_000,
                -1, -50));
        await().atMost(WAIT).untilAsserted(() -> assertThat(generation.get(job.id())).satisfies(running -> {
            assertThat(running.status()).isEqualTo(GenerationStatus.RUNNING);
            assertThat(running.bestScore()).isEqualTo("-1hard/-50soft");
            assertThat(running.progress()).containsExactly(new GenerationJobDto.ProgressPoint(2.0, -1, -50));
        }));

        GenerationCompleted completed = new GenerationCompleted(EventEnvelope.create(clock), job.id(),
                GenerationStatus.SOLVED, 0, -12, assignments(problem), List.of(
                new GenerationCompleted.ConstraintScore("Вікна груп", 1, "0hard/-10soft")), null);
        send(RozkladEvents.GENERATION_COMPLETED, completed);
        await().atMost(WAIT).untilAsserted(() -> assertThat(generation.get(job.id()).status())
                .isEqualTo(GenerationStatus.SOLVED));

        GenerationJobDto solved = generation.get(job.id());
        assertThat(solved.resultVersionId()).isNotNull();
        assertThat(solved.bestScore()).isEqualTo("0hard/-12soft");
        assertThat(solved.analysis()).extracting(GenerationCompleted.ConstraintScore::constraint)
                .containsExactly("Вікна груп");
        assertThat(solved.metricsBefore()).isNotNull();
        assertThat(solved.metricsAfter()).isNotNull();
        TimetableVersion generated = versions.findById(solved.resultVersionId()).orElseThrow();
        assertThat(generated.getSource()).isEqualTo(VersionSource.GENERATED);
        assertThat(generated.getStatus()).isEqualTo(VersionStatus.DRAFT);
        assertThat(generated.getGenerationJobId()).isEqualTo(job.id());
        List<TemplateLesson> lessons = templateLessons.findAllByVersionId(generated.getId());
        assertThat(lessons).hasSize(4);
        assertThat(lessons).filteredOn(lesson -> lesson.getDisciplineId().equals(data.stp.getId()))
                .extracting(TemplateLesson::getParity)
                .containsExactlyInAnyOrder(Parity.BOTH, Parity.DENOMINATOR);
        assertThat(lessons).filteredOn(TemplateLesson::isPinned).hasSize(2);

        // повторна доставка того самого результату: нової версії не з'являється
        send(RozkladEvents.GENERATION_COMPLETED, completed);
        GenerationProgress marker = new GenerationProgress(EventEnvelope.create(clock), UUID.randomUUID(), 1, 0, 0);
        send(RozkladEvents.GENERATION_PROGRESS, marker);
        await().atMost(WAIT).until(() -> processed(marker.eventId()));
        assertThat(versions.findAllBySemesterIdOrderByCreatedAtDesc(data.semester.getId()))
                .filteredOn(version -> version.getSource() == VersionSource.GENERATED).hasSize(1);
    }

    @Test
    void cancelledJobIgnoresALateResultAndTellsTheScheduler() {
        Queue cancels = probe(RozkladEvents.GENERATION_CANCEL);
        GenerationJobDto job = generation.start(command(), "admin");

        GenerationJobDto cancelled = generation.cancel(job.id());

        assertThat(cancelled.status()).isEqualTo(GenerationStatus.CANCELLED);
        assertThat(cancelled.finishedAt()).isNotNull();
        assertThat(rabbitTemplate.receiveAndConvert(cancels.getName(), 15_000))
                .isInstanceOfSatisfying(GenerationCancelRequested.class,
                        event -> assertThat(event.jobId()).isEqualTo(job.id()));
        assertThat(generation.cancel(job.id()).status()).as("cancel is idempotent")
                .isEqualTo(GenerationStatus.CANCELLED);

        GenerationCompleted late = new GenerationCompleted(EventEnvelope.create(clock), job.id(),
                GenerationStatus.SOLVED, 0, 0, List.of(), List.of(), null);
        send(RozkladEvents.GENERATION_COMPLETED, late);
        await().atMost(WAIT).until(() -> processed(late.eventId()));

        assertThat(generation.get(job.id())).satisfies(ignored -> {
            assertThat(ignored.status()).isEqualTo(GenerationStatus.CANCELLED);
            assertThat(ignored.resultVersionId()).isNull();
        });
        assertThat(generation.start(command(), "admin").status()).as("the semester is free again")
                .isEqualTo(GenerationStatus.QUEUED);
    }

    @Test
    void invalidSolutionFailsTheJobInsteadOfCreatingAVersion() {
        GenerationJobDto job = generation.start(command(), "admin");
        String halfUnit = transaction.execute(status -> StreamSupport.stream(
                        jobs.findById(job.id()).orElseThrow().getPlan().get("units").spliterator(), false)
                .filter(unit -> "HALF".equals(unit.get("weight").asString()))
                .map(unit -> unit.get("unitId").asString())
                .findFirst().orElseThrow());

        send(RozkladEvents.GENERATION_COMPLETED, new GenerationCompleted(EventEnvelope.create(clock), job.id(),
                GenerationStatus.SOLVED, 0, 0, List.of(new Assignment(halfUnit, 1, 2, Parity.BOTH, null)), List.of(),
                null));

        await().atMost(WAIT).untilAsserted(() -> assertThat(generation.get(job.id())).satisfies(failed -> {
            assertThat(failed.status()).isEqualTo(GenerationStatus.FAILED);
            assertThat(failed.error()).startsWith("Планувальник повернув некоректний результат")
                    .contains("без чисельника чи знаменника");
            assertThat(failed.resultVersionId()).isNull();
        }));
    }

    @Test
    void failedSolverReportsItsError() {
        GenerationJobDto job = generation.start(command(), "admin");

        send(RozkladEvents.GENERATION_COMPLETED, new GenerationCompleted(EventEnvelope.create(clock), job.id(),
                GenerationStatus.FAILED, -3, -100, List.of(), List.of(), "Немає допустимого розкладу для 4КІ1"));

        await().atMost(WAIT).untilAsserted(() -> assertThat(generation.get(job.id())).satisfies(failed -> {
            assertThat(failed.status()).isEqualTo(GenerationStatus.FAILED);
            assertThat(failed.error()).isEqualTo("Немає допустимого розкладу для 4КІ1");
            assertThat(failed.finishedAt()).isNotNull();
        }));
    }

    @Test
    void unreadableResultGoesToTheDeadLetterQueue() {
        String deadLetters = RozkladEvents.Queues.deadLetterQueue(RozkladEvents.Queues.CORE_SCHEDULER_RESULTS);
        amqpAdmin.purgeQueue(deadLetters, false);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);

        rabbitTemplate.send(RozkladEvents.EXCHANGE, RozkladEvents.GENERATION_COMPLETED,
                new Message("{\"jobId\": ".getBytes(StandardCharsets.UTF_8), properties));

        await().atMost(Duration.ofSeconds(30)).until(() -> rabbitTemplate.receive(deadLetters) != null);
    }

    private GenerationRequestCommand command() {
        return new GenerationRequestCommand(data.semester.getId(), List.of(data.group4ki1.getId()), null,
                baseVersionId, 30, null);
    }

    /** A feasible answer: wholes on Tuesday/Wednesday, the half in a denominator week, pinned units at their pins. */
    private static List<Assignment> assignments(ProblemSnapshot problem) {
        List<Assignment> assignments = new ArrayList<>();
        int pair = 1;
        for (ProblemSnapshot.Unit unit : problem.units()) {
            if (unit.pinned() != null) {
                assignments.add(new Assignment(unit.unitId(), unit.pinned().weekday(), unit.pinned().pair(),
                        unit.pinned().parity(), unit.pinned().roomId()));
            } else if (unit.weight() == UnitWeight.HALF) {
                assignments.add(new Assignment(unit.unitId(), 3, 3, Parity.DENOMINATOR, null));
            } else {
                assignments.add(new Assignment(unit.unitId(), 3, pair++, Parity.NUMERATOR, null));
            }
        }
        return assignments;
    }

    private void send(String routingKey, Object event) {
        rabbitTemplate.convertAndSend(RozkladEvents.EXCHANGE, routingKey, event);
    }

    private boolean processed(UUID eventId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class,
                eventId);
        return count != null && count == 1;
    }

    private Queue probe(String routingKey) {
        Queue probe = new AnonymousQueue();
        amqpAdmin.declareQueue(probe);
        amqpAdmin.declareBinding(BindingBuilder.bind(probe).to(new TopicExchange(RozkladEvents.EXCHANGE))
                .with(routingKey));
        return probe;
    }
}
