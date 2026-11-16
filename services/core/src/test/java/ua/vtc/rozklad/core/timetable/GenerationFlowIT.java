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
import ua.vtc.rozklad.contracts.GenerationStopReason;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.EventEnvelope;
import ua.vtc.rozklad.contracts.events.GenerationCancelRequested;
import ua.vtc.rozklad.contracts.events.GenerationCompleted;
import ua.vtc.rozklad.contracts.events.GenerationCompleted.Assignment;
import ua.vtc.rozklad.contracts.events.GenerationProgress;
import ua.vtc.rozklad.contracts.events.GenerationRequested;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.contracts.events.RozkladEvents;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.load.LoadItem;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.substitution.PreferenceKind;
import ua.vtc.rozklad.core.substitution.TeacherPreference;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.timetable.internal.generation.GenerationJobDto;
import ua.vtc.rozklad.core.timetable.internal.generation.GenerationOutcome;
import ua.vtc.rozklad.core.timetable.internal.generation.GenerationRequestCommand;
import ua.vtc.rozklad.core.timetable.internal.generation.GenerationService;
import ua.vtc.rozklad.core.timetable.internal.generation.LoadFitCheck;
import ua.vtc.rozklad.core.timetable.internal.importing.ImportTimetableCommand;
import ua.vtc.rozklad.core.timetable.internal.importing.TimetableImportService;

/**
 * Generation orchestration over RabbitMQ: the problem snapshot leaves through the outbox, progress
 * and results come back on {@code core.q.scheduler-results}, a solved result becomes a draft, a
 * redelivered result is ignored, a cancelled queued job ignores late results, a stopped running job keeps
 * the best timetable the scheduler returns, and a request without a time limit runs in auto mode.
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
        assertThat(problem.groups()).as("the college setting «Максимум пар у пʼятницю», default 3")
                .allSatisfy(group -> assertThat(group.fridayMaxPairs()).isEqualTo(3));
        assertThat(problem.teachers()).filteredOn(teacher -> teacher.id().equals(data.krentsin.getId()))
                .singleElement()
                .satisfies(teacher -> assertThat(teacher.unavailable()).containsExactly(new ProblemSnapshot.Timeslot(1, 1)));
        assertThat(problem.teachers()).as("the college setting, default 4")
                .allSatisfy(teacher -> assertThat(teacher.maxPairsPerDay()).isEqualTo(4));
        Map<String, ProblemSnapshot.Unit> units = problem.units().stream()
                .collect(Collectors.toMap(ProblemSnapshot.Unit::unitId, Function.identity()));
        assertThat(units.values()).extracting(ProblemSnapshot.Unit::weight)
                .containsExactlyInAnyOrder(UnitWeight.WHOLE, UnitWeight.HALF, UnitWeight.WHOLE, UnitWeight.WHOLE);
        ProblemSnapshot.Unit pinnedKm = units.values().stream()
                .filter(unit -> unit.disciplineId().equals(data.km.getId())).findFirst().orElseThrow();
        assertThat(pinnedKm.pinned()).isEqualTo(new ProblemSnapshot.PinnedSlot(4, 2, Parity.BOTH, data.lab6.getId()));
        assertThat(pinnedKm.requiredRoomType()).as("no hard room type is derived from the base (genfix repair)")
                .isNull();
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
        assertThat(solved.timeLimitSeconds()).as("an old request keeps its limit").isEqualTo(30);
        assertThat(solved.stopReason()).as("a completion of the first schema has none").isNull();
        assertThat(solved.baseHardScore()).as("nor a base score").isNull();
        assertThat(solved.baseSoftScore()).isNull();
        assertThat(solved.stopRequested()).isFalse();
        assertThat(solved.bestScore()).isEqualTo("0hard/-12soft");
        assertThat(solved.analysis()).extracting(GenerationCompleted.ConstraintScore::constraint)
                .containsExactly("Вікна груп");
        assertThat(solved.metricsBefore()).isNotNull();
        assertThat(solved.metricsAfter()).isNotNull();
        assertThat(solved.metricsBefore().hardConflicts()).isZero();
        assertThat(solved.metricsAfter().hardConflicts()).isZero();
        assertThat(solved.hardScore()).isZero();
        assertThat(solved.softScore()).isEqualTo(-12L);
        assertThat(solved.groups()).extracting(GroupRef::code).containsExactly("4КІ1");
        assertThat(solved.scope().groupIds()).containsExactly(data.group4ki1.getId());
        assertThat(solved.weights()).isEqualTo(GenerationWeights.DEFAULT);
        assertThat(solved.outcome()).as("no free base lesson of the scope to compare with")
                .usingRecursiveComparison().ignoringFields("roomAssigned", "added", "kept")
                .isEqualTo(new GenerationOutcome(0, 0, 0, List.of()));
        long pinned = problem.units().stream().filter(unit -> unit.pinned() != null).count();
        assertThat(solved.outcome().added()).as("every free unit is new: no free base lesson")
                .isEqualTo((int) (problem.units().size() - pinned));
        assertThat(solved.outcome().kept()).as("the lesson outside the scope stays where it is")
                .isEqualTo((int) pinned).isPositive();
        TimetableVersion generated = versions.findById(solved.resultVersionId()).orElseThrow();
        assertThat(generated.getSource()).isEqualTo(VersionSource.GENERATED);
        assertThat(generated.getStatus()).isEqualTo(VersionStatus.DRAFT);
        assertThat(generated.getGenerationJobId()).isEqualTo(job.id());
        List<TemplateLesson> lessons = templateLessons.findAllByVersionId(generated.getId());
        assertThat(lessons).hasSize(4);
        assertThat(lessons).filteredOn(lesson -> lesson.getDisciplineId().equals(data.stp.getId()))
                .extracting(TemplateLesson::getParity)
                .containsExactlyInAnyOrder(Parity.BOTH, Parity.DENOMINATOR);
        // B1: лише КМ групи 4КІ1 закріпив диспетчер; заняття 4КІ2 поза вибором
        // надсилалися закріпленими лише для розв'язувача
        assertThat(lessons).filteredOn(TemplateLesson::isPinned).singleElement()
                .satisfies(lesson -> assertThat(lesson.getDisciplineId()).isEqualTo(data.km.getId()));
        assertThat(lessons).filteredOn(lesson -> lesson.getGroupId().equals(data.group4ki2.getId())).singleElement()
                .satisfies(lesson -> assertThat(lesson.isPinned()).isFalse());

        // повторна доставка того самого результату: нової версії не з'являється
        send(RozkladEvents.GENERATION_COMPLETED, completed);
        GenerationProgress marker = new GenerationProgress(EventEnvelope.create(clock), UUID.randomUUID(), 1, 0, 0);
        send(RozkladEvents.GENERATION_PROGRESS, marker);
        await().atMost(WAIT).until(() -> processed(marker.eventId()));
        assertThat(versions.findAllBySemesterIdOrderByCreatedAtDesc(data.semester.getId()))
                .filteredOn(version -> version.getSource() == VersionSource.GENERATED).hasSize(1);
    }

    /**
     * Round 7: the room belongs to the teacher. A free unit names its teacher's usual room for the
     * discipline (Непийвода teaches НДЕКСМ in 304 in the base, so 4КІ1's НДЕКСМ goes there, although
     * her room over all lessons is 6 by the lower number); a teacher without lessons has none; the
     * group's home room is not sent any more.
     */
    @Test
    void freeUnitsNameTheTeachersUsualRoom() {
        transaction.executeWithoutResult(status -> {
            LoadItem ndeksm = new LoadItem(data.semester.getId(), data.group4ki1.getId(), data.ndeksm.getId(),
                    BigDecimal.valueOf(2), 44);
            ndeksm.assignTeacher(0, data.nepyivoda.getId());
            em.persist(ndeksm);
        });
        jdbc.update("UPDATE student_group SET home_room_id = ? WHERE id = ?", data.room304.getId(),
                data.group4ki1.getId());
        Queue requests = probe(RozkladEvents.GENERATION_REQUESTED);

        generation.start(command(), "admin");

        GenerationRequested request = (GenerationRequested) rabbitTemplate.receiveAndConvert(requests.getName(),
                15_000);
        assertThat(request).isNotNull();
        List<ProblemSnapshot.Unit> units = request.problem().units();
        assertThat(units).allSatisfy(unit -> assertThat(unit.homeRoomId()).isNull());
        assertThat(units).filteredOn(unit -> unit.disciplineId().equals(data.ndeksm.getId())
                        && unit.groupId().equals(data.group4ki1.getId()))
                .singleElement()
                .satisfies(unit -> {
                    assertThat(unit.pinned()).isNull();
                    assertThat(unit.teacherRoomId()).isEqualTo(data.room304.getId());
                });
        assertThat(units).filteredOn(unit -> unit.disciplineId().equals(data.stp.getId()))
                .isNotEmpty()
                .allSatisfy(unit -> assertThat(unit.teacherRoomId()).as("Кренцін has no lessons yet").isNull());
        assertThat(units).filteredOn(unit -> unit.pinned() != null)
                .allSatisfy(unit -> assertThat(unit.teacherRoomId()).isNull());
    }

    /** The scheduler has no streams: the stream of the base version stays one lesson of one teacher. */
    @Test
    void streamOfTheBaseVersionStaysTogether() {
        UUID stream = UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            // інша група потоку ходить лише в чисельник: той самий слот і викладач
            TemplateLesson secondGroup = new TemplateLesson(baseVersionId, 4, 2, Parity.NUMERATOR, data.group4ki2.getId(), 0,
                    data.km.getId());
            secondGroup.setTeacherId(data.nepyivoda.getId());
            secondGroup.setRoomId(data.lab6.getId());
            em.persist(secondGroup);
        });
        jdbc.update("UPDATE template_lesson SET stream_id = ? WHERE version_id = ? AND discipline_id = ?", stream,
                baseVersionId, data.km.getId());
        Queue requests = probe(RozkladEvents.GENERATION_REQUESTED);

        GenerationJobDto job = generation.start(new GenerationRequestCommand(data.semester.getId(),
                List.of(data.group4ki1.getId(), data.group4ki2.getId()), null, baseVersionId, 30, null), "admin");

        GenerationRequested request = (GenerationRequested) rabbitTemplate.receiveAndConvert(requests.getName(),
                15_000);
        assertThat(request).isNotNull();
        List<ProblemSnapshot.Unit> streamUnits = request.problem().units().stream()
                .filter(unit -> unit.disciplineId().equals(data.km.getId())).toList();
        assertThat(streamUnits).hasSize(2).allSatisfy(unit -> assertThat(unit.pinned()).isNotNull()
                .extracting(ProblemSnapshot.PinnedSlot::weekday, ProblemSnapshot.PinnedSlot::pair)
                .containsExactly(4, 2));
        // для планувальника викладач і аудиторія потоку зайняті лише одним заняттям
        assertThat(streamUnits).filteredOn(unit -> data.nepyivoda.getId().equals(unit.teacherId())).hasSize(1);
        assertThat(streamUnits).filteredOn(unit -> unit.pinned().roomId() != null).hasSize(1);

        send(RozkladEvents.GENERATION_COMPLETED, new GenerationCompleted(EventEnvelope.create(clock), job.id(),
                GenerationStatus.SOLVED, 0, -3, assignments(request.problem()), List.of(), null));
        await().atMost(WAIT).untilAsserted(() -> assertThat(generation.get(job.id()).status())
                .isEqualTo(GenerationStatus.SOLVED));

        List<TemplateLesson> generated = templateLessons.findAllByVersionId(generation.get(job.id()).resultVersionId());
        assertThat(generated).filteredOn(lesson -> lesson.getDisciplineId().equals(data.km.getId()))
                .hasSize(2)
                .allSatisfy(lesson -> {
                    assertThat(lesson.getStreamId()).isEqualTo(stream);
                    assertThat(lesson.getTeacherId()).isEqualTo(data.nepyivoda.getId());
                    assertThat(lesson.getRoomId()).isEqualTo(data.lab6.getId());
                    assertThat(lesson.getWeekday()).isEqualTo(4);
                    assertThat(lesson.getPairNumber()).isEqualTo(2);
                });
    }

    /**
     * B3 and M2: the base has 4КІ1's СТП with Непийвода, the load names Кренцін. The unit keeps the base
     * teacher and starts at the base slot; the result moves it, and the job says «Переставлено 1 з 1».
     */
    @Test
    void aBaseLessonKeepsItsTeacherAndTheOutcomeCountsTheMove() {
        transaction.executeWithoutResult(status -> {
            TemplateLesson stpWithNepyivoda = new TemplateLesson(baseVersionId, 1, 3, Parity.BOTH,
                    data.group4ki1.getId(), 0, data.stp.getId());
            stpWithNepyivoda.setTeacherId(data.nepyivoda.getId());
            stpWithNepyivoda.setRoomId(data.room304.getId());
            em.persist(stpWithNepyivoda);
        });
        Queue requests = probe(RozkladEvents.GENERATION_REQUESTED);

        GenerationJobDto job = generation.start(command(), "admin");

        GenerationRequested request = (GenerationRequested) rabbitTemplate.receiveAndConvert(requests.getName(),
                15_000);
        assertThat(request).isNotNull();
        assertThat(request.problem().units())
                .filteredOn(unit -> unit.disciplineId().equals(data.stp.getId()) && unit.weight() == UnitWeight.WHOLE)
                .singleElement()
                .satisfies(unit -> {
                    assertThat(unit.teacherId()).isEqualTo(data.nepyivoda.getId());
                    assertThat(unit.initial()).isEqualTo(new ProblemSnapshot.PinnedSlot(1, 3, Parity.BOTH,
                            data.room304.getId()));
                });

        send(RozkladEvents.GENERATION_COMPLETED, new GenerationCompleted(EventEnvelope.create(clock), job.id(),
                GenerationStatus.SOLVED, 0, -3, assignments(request.problem()), List.of(), null));
        await().atMost(WAIT).untilAsserted(() -> assertThat(generation.get(job.id()).status())
                .isEqualTo(GenerationStatus.SOLVED));

        GenerationJobDto solved = generation.get(job.id());
        assertThat(solved.outcome()).usingRecursiveComparison().ignoringFields("added", "kept")
                .isEqualTo(new GenerationOutcome(1, 0, 1, List.of(
                        new GenerationOutcome.GroupOutcome(GroupRef.of(data.group4ki1), 1, 1)), 0, null, null));
        assertThat(templateLessons.findAllByVersionId(solved.resultVersionId()))
                .filteredOn(lesson -> lesson.getDisciplineId().equals(data.stp.getId())
                        && lesson.getParity() == Parity.BOTH)
                .singleElement()
                .satisfies(lesson -> assertThat(lesson.getTeacherId()).isEqualTo(data.nepyivoda.getId()));
    }

    /** B9: 4КІ1 (shift 1, 20 slots) cannot take 21,5 pairs a week: a 422 before any job is stored. */
    @Test
    void loadThatDoesNotFitTheGridIsRefusedBeforeTheJobIsStored() {
        transaction.executeWithoutResult(status -> {
            LoadItem extra = new LoadItem(data.semester.getId(), data.group4ki1.getId(), data.ndeksm.getId(),
                    BigDecimal.valueOf(36), 792);
            extra.assignTeacher(0, data.nepyivoda.getId());
            em.persist(extra);
        });
        long before = jobs.count();

        assertThatThrownBy(() -> generation.start(command(), "admin"))
                .isInstanceOfSatisfying(BusinessRuleException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("GENERATION_LOAD_EXCEEDS_SLOTS");
                    assertThat(error.getProperties().get("problems")).asInstanceOf(
                                    org.assertj.core.api.InstanceOfAssertFactories.list(LoadFitCheck.Problem.class))
                            .extracting(LoadFitCheck.Problem::label)
                            .contains("4КІ1");
                });
        assertThat(jobs.count()).isEqualTo(before);
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

    /** Auto mode: no limit in the request or the job; the scheduler's reason is kept with the draft. */
    @Test
    void requestWithoutTimeLimitRunsInAutoModeAndKeepsTheStopReason() {
        Queue requests = probe(RozkladEvents.GENERATION_REQUESTED);

        GenerationJobDto job = generation.start(autoCommand(), "admin");

        assertThat(job.timeLimitSeconds()).isNull();
        GenerationRequested request = (GenerationRequested) rabbitTemplate.receiveAndConvert(requests.getName(),
                15_000);
        assertThat(request).isNotNull();
        assertThat(request.autoMode()).isTrue();
        Boolean storedLimit = transaction.execute(status -> jobs.findById(job.id()).orElseThrow().getParams()
                .has("timeLimitSeconds"));
        assertThat(storedLimit).isFalse();

        send(RozkladEvents.GENERATION_COMPLETED, new GenerationCompleted(EventEnvelope.create(clock), job.id(),
                GenerationStatus.SOLVED, 0, -8, assignments(request.problem()), List.of(), null,
                GenerationStopReason.NO_IMPROVEMENT, new GenerationCompleted.BaseScore(-2, -40)));
        await().atMost(WAIT).untilAsserted(() -> assertThat(generation.get(job.id()).status())
                .isEqualTo(GenerationStatus.SOLVED));

        assertThat(generation.get(job.id())).satisfies(solved -> {
            assertThat(solved.stopReason()).isEqualTo(GenerationStopReason.NO_IMPROVEMENT);
            // «Було» жорстких порушень — з оцінки бази розвʼязувачем, збереженої на задачі
            assertThat(solved.baseHardScore()).isEqualTo(-2L);
            assertThat(solved.baseSoftScore()).isEqualTo(-40L);
            assertThat(solved.resultVersionId()).isNotNull();
        });
    }

    /** «Зупинити» on a running job: it stays active until the scheduler returns the best timetable, which is kept. */
    @Test
    void stoppedRunningJobKeepsTheBestTimetableAsADraft() {
        Queue requests = probe(RozkladEvents.GENERATION_REQUESTED);
        Queue cancels = probe(RozkladEvents.GENERATION_CANCEL);
        GenerationJobDto job = generation.start(autoCommand(), "admin");
        GenerationRequested request = (GenerationRequested) rabbitTemplate.receiveAndConvert(requests.getName(),
                15_000);
        assertThat(request).isNotNull();
        send(RozkladEvents.GENERATION_PROGRESS, new GenerationProgress(EventEnvelope.create(clock), job.id(), 2_000,
                0, -40));
        await().atMost(WAIT).untilAsserted(() -> assertThat(generation.get(job.id()).status())
                .isEqualTo(GenerationStatus.RUNNING));

        GenerationJobDto stopping = generation.cancel(job.id());

        assertThat(stopping.status()).isEqualTo(GenerationStatus.RUNNING);
        assertThat(stopping.stopRequested()).isTrue();
        assertThat(stopping.finishedAt()).isNull();
        assertThat(rabbitTemplate.receiveAndConvert(cancels.getName(), 15_000))
                .isInstanceOfSatisfying(GenerationCancelRequested.class,
                        event -> assertThat(event.jobId()).isEqualTo(job.id()));
        GenerationJobDto again = generation.cancel(job.id());
        assertThat(again.stopRequested()).as("stopping again changes nothing").isTrue();
        assertThat(again.status()).isEqualTo(GenerationStatus.RUNNING);
        assertThatThrownBy(() -> generation.start(autoCommand(), "admin")).as("still the semester's active job")
                .isInstanceOf(ConflictException.class);

        // планувальник зупинився й повернув найкращий розклад на момент зупинки
        send(RozkladEvents.GENERATION_COMPLETED, new GenerationCompleted(EventEnvelope.create(clock), job.id(),
                GenerationStatus.CANCELLED, 0, -35, assignments(request.problem()), List.of(), null,
                GenerationStopReason.STOPPED));
        await().atMost(WAIT).untilAsserted(() -> assertThat(generation.get(job.id()).status())
                .isEqualTo(GenerationStatus.SOLVED));

        GenerationJobDto stopped = generation.get(job.id());
        assertThat(stopped.stopReason()).isEqualTo(GenerationStopReason.STOPPED);
        assertThat(stopped.bestScore()).isEqualTo("0hard/-35soft");
        assertThat(stopped.resultVersionId()).isNotNull();
        TimetableVersion draft = versions.findById(stopped.resultVersionId()).orElseThrow();
        assertThat(draft.getStatus()).isEqualTo(VersionStatus.DRAFT);
        assertThat(templateLessons.findAllByVersionId(draft.getId())).hasSize(4);
        assertThat(generation.cancel(job.id()).status()).as("no 409 for a stop that already ended")
                .isEqualTo(GenerationStatus.SOLVED);
    }

    /** A job stopped before the scheduler had a complete timetable ends CANCELLED without a draft. */
    @Test
    void stoppedJobWithoutATimetableIsCancelled() {
        GenerationJobDto job = generation.start(autoCommand(), "admin");
        send(RozkladEvents.GENERATION_PROGRESS, new GenerationProgress(EventEnvelope.create(clock), job.id(), 1_000,
                -3, -40));
        await().atMost(WAIT).untilAsserted(() -> assertThat(generation.get(job.id()).status())
                .isEqualTo(GenerationStatus.RUNNING));
        generation.cancel(job.id());

        send(RozkladEvents.GENERATION_COMPLETED, new GenerationCompleted(EventEnvelope.create(clock), job.id(),
                GenerationStatus.CANCELLED, 0, 0, List.of(), List.of(), null));
        await().atMost(WAIT).untilAsserted(() -> assertThat(generation.get(job.id()).status())
                .isEqualTo(GenerationStatus.CANCELLED));

        assertThat(generation.get(job.id())).satisfies(cancelled -> {
            assertThat(cancelled.resultVersionId()).isNull();
            assertThat(cancelled.stopReason()).isNull();
            assertThat(cancelled.stopRequested()).isTrue();
        });
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

    /**
     * The admin deleted a load item (or re-derived the load) while the solver ran: the result is still
     * applied, the lessons of that item lose only the link to it, and the version notes say so.
     */
    @Test
    void loadItemDeletedDuringTheRunIsLeftOutOfItsLessons() {
        Queue requests = probe(RozkladEvents.GENERATION_REQUESTED);
        GenerationJobDto job = generation.start(command(), "admin");
        GenerationRequested request = (GenerationRequested) rabbitTemplate.receiveAndConvert(requests.getName(),
                15_000);
        assertThat(request).isNotNull();
        UUID stpItem = jdbc.queryForObject("SELECT id FROM load_item WHERE discipline_id = ?", UUID.class,
                data.stp.getId());
        jdbc.update("DELETE FROM load_item WHERE id = ?", stpItem);

        send(RozkladEvents.GENERATION_COMPLETED, new GenerationCompleted(EventEnvelope.create(clock), job.id(),
                GenerationStatus.SOLVED, 0, -12, assignments(request.problem()), List.of(), null));

        await().atMost(WAIT).untilAsserted(() -> assertThat(generation.get(job.id()).status())
                .isEqualTo(GenerationStatus.SOLVED));
        TimetableVersion generated = versions.findById(generation.get(job.id()).resultVersionId()).orElseThrow();
        assertThat(generated.getNotes()).contains("навантаження видалили під час генерації");
        List<TemplateLesson> lessons = templateLessons.findAllByVersionId(generated.getId());
        assertThat(lessons).filteredOn(lesson -> lesson.getDisciplineId().equals(data.stp.getId())).hasSize(2)
                .allSatisfy(lesson -> {
                    assertThat(lesson.getLoadItemId()).isNull();
                    assertThat(lesson.getTeacherId()).isEqualTo(data.krentsin.getId());
                });
        assertThat(lessons).filteredOn(lesson -> lesson.getDisciplineId().equals(data.km.getId()))
                .allSatisfy(lesson -> assertThat(lesson.getLoadItemId()).isNotNull());
    }

    /** A discipline the result needs was deleted during the run: the job fails with a clear reason at once. */
    @Test
    void disciplineDeletedDuringTheRunFailsTheJobWithItsReason() {
        Queue requests = probe(RozkladEvents.GENERATION_REQUESTED);
        GenerationJobDto job = generation.start(command(), "admin");
        GenerationRequested request = (GenerationRequested) rabbitTemplate.receiveAndConvert(requests.getName(),
                15_000);
        assertThat(request).isNotNull();
        jdbc.update("DELETE FROM load_item WHERE discipline_id = ?", data.stp.getId());
        jdbc.update("DELETE FROM discipline WHERE id = ?", data.stp.getId());

        GenerationCompleted completed = new GenerationCompleted(EventEnvelope.create(clock), job.id(),
                GenerationStatus.SOLVED, 0, -12, assignments(request.problem()), List.of(), null);
        send(RozkladEvents.GENERATION_COMPLETED, completed);

        await().atMost(WAIT).untilAsserted(() -> assertThat(generation.get(job.id())).satisfies(failed -> {
            assertThat(failed.status()).isEqualTo(GenerationStatus.FAILED);
            assertThat(failed.error()).contains("видалили групу або дисципліну");
            assertThat(failed.resultVersionId()).isNull();
        }));
        assertThat(processed(completed.eventId())).as("handled, not dead-lettered").isTrue();
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

    /** The same scope without a time limit (auto mode). */
    private GenerationRequestCommand autoCommand() {
        return new GenerationRequestCommand(data.semester.getId(), List.of(data.group4ki1.getId()), null,
                baseVersionId, null, null);
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
