package ua.vtc.rozklad.core.timetable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.events.RozkladEvents;
import ua.vtc.rozklad.contracts.events.ScheduleChangeReason;
import ua.vtc.rozklad.contracts.events.ScheduleChanged;
import ua.vtc.rozklad.contracts.events.TimetableVersionPublished;
import ua.vtc.rozklad.core.calendar.CalendarPeriod;
import ua.vtc.rozklad.core.calendar.CalendarPeriodKind;
import ua.vtc.rozklad.core.calendar.StudyMode;
import ua.vtc.rozklad.core.calendar.WeekParityOverride;
import ua.vtc.rozklad.core.shared.domain.ReasonCode;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.timetable.internal.importing.ImportTimetableCommand;
import ua.vtc.rozklad.core.timetable.internal.importing.ImportTimetableResult;
import ua.vtc.rozklad.core.timetable.internal.importing.TimetableImportService;
import ua.vtc.rozklad.core.timetable.internal.publication.PublicationResult;
import ua.vtc.rozklad.core.timetable.internal.publication.PublicationService;
import ua.vtc.rozklad.core.timetable.internal.version.MoveCommand;
import ua.vtc.rozklad.core.timetable.internal.version.TemplateLessonService;
import ua.vtc.rozklad.core.timetable.internal.version.TimetableVersionService;

/**
 * Import → publication → dated lessons and public schedule on real PostgreSQL and RabbitMQ:
 * week parity with an override, a holiday, a practice of one group, an online period, an applied
 * substitution that survives republication, conflicts reported to the dispatcher and the events.
 */
@CoreIntegrationTest
class TimetablePublicationIT {

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private EntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private RestTestClient client;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private TimetableImportService imports;

    @Autowired
    private PublicationService publication;

    @Autowired
    private TimetableVersionService versions;

    @Autowired
    private TemplateLessonService templateLessons;

    @Autowired
    private TimetableVersionRepository versionRepository;

    @Autowired
    private LessonConflictChecker conflictChecker;

    private TimetableItData data;

    @BeforeEach
    void catalogAndCalendar() {
        data = new TimetableItData(LocalDate.now(clock)).create(transaction, em);
        transaction.executeWithoutResult(status -> {
            UUID semesterId = data.semester.getId();
            em.persist(new CalendarPeriod(semesterId, CalendarPeriodKind.HOLIDAY, "День самоврядування",
                    data.monday(9), data.monday(9)));
            CalendarPeriod practice = new CalendarPeriod(semesterId, CalendarPeriodKind.PRACTICE,
                    "4КІ2 – Переддипломна практика", data.monday(6), data.monday(6).plusDays(4));
            practice.applyToGroups(List.of(data.group4ki2.getId()));
            em.persist(practice);
            CalendarPeriod online = new CalendarPeriod(semesterId, CalendarPeriodKind.STUDY_MODE,
                    "Групи 4КІ навчаються дистанційно", data.monday(7), data.monday(8).plusDays(4));
            online.setStudyMode(StudyMode.ONLINE);
            online.applyToCourses(List.of(4));
            em.persist(online);
            // 5-й тиждень за правилом чисельник, але адміністратор зробив його знаменником
            em.persist(new WeekParityOverride(semesterId, data.monday(5), Parity.DENOMINATOR));
        });
    }

    @AfterEach
    void cleanUp() {
        TimetableItData.cleanUp(jdbc);
    }

    @Test
    void publishedVersionIsMaterializedWithParityHolidaysPracticeAndStudyMode() {
        ImportTimetableResult imported = imports.importTimetable(importCommand());
        assertThat(imported.lessonCount()).isEqualTo(4);
        assertThat(imported.hardConflicts()).isZero();

        PublicationResult result = publication.publish(imported.versionId(), data.semester.getStartsOn(), "admin");

        assertThat(result.materializedLessons()).isEqualTo(count("SELECT count(*) FROM lesson"));
        assertThat(result.keptLessons()).isZero();
        assertThat(result.conflicts()).isEmpty();
        UUID group = data.group4ki1.getId();
        assertThat(lessonsOf(group, data.monday(1).plusDays(2))).as("Wednesday, week 1 (numerator)").isEqualTo(1);
        assertThat(lessonsOf(group, data.monday(2).plusDays(2))).as("Wednesday, week 2 (denominator)").isZero();
        assertThat(lessonsOf(group, data.monday(5).plusDays(2))).as("week 5 overridden to denominator").isZero();
        assertThat(lessonsOf(group, data.monday(1))).as("Monday before the semester start").isZero();
        assertThat(lessonsOf(group, data.monday(9))).as("holiday").isZero();
        assertThat(lessonsOf(data.group4ki2.getId(), data.monday(6))).as("practice of 4КІ2").isZero();
        assertThat(lessonsOf(group, data.monday(6))).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM lesson WHERE date = ? AND group_id = ? AND online", data.monday(7), group))
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM lesson WHERE date = ? AND online", data.monday(6))).isZero();
        assertThat(versionRepository.findById(imported.versionId()).orElseThrow()).satisfies(version -> {
            assertThat(version.getStatus()).isEqualTo(VersionStatus.PUBLISHED);
            assertThat(version.getPublishedBy()).isEqualTo("admin");
            assertThat(version.getMetrics().get("brokenDays").asInt()).isEqualTo(1);
        });
    }

    /** A retry of the importer's call with the same Idempotency-Key returns the first draft. */
    @Test
    void repeatedImportWithTheSameKeyReturnsTheFirstDraft() {
        ImportTimetableResult first = imports.importTimetable(importCommand(), "import-job:test:timetable");
        ImportTimetableResult repeat = imports.importTimetable(importCommand(), "import-job:test:timetable");

        assertThat(repeat).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM timetable_version WHERE semester_id = ?", data.semester.getId()))
                .isEqualTo(1);
        ImportTimetableCommand other = new ImportTimetableCommand(data.semester.getId(), "Інша назва",
                importCommand().lessons());
        assertThatThrownBy(() -> imports.importTimetable(other, "import-job:test:timetable"))
                .isInstanceOf(ConflictException.class);
        assertThat(imports.importTimetable(importCommand(), null).versionId()).isNotEqualTo(first.versionId());
    }

    @Test
    void publicScheduleShowsTimesNotesAndAnswers304WhenUnchanged() {
        publication.publish(imports.importTimetable(importCommand()).versionId(), data.semester.getStartsOn(), "admin");
        LocalDate monday = data.monday(7);

        var result = client.get().uri("/api/public/schedule/group/{id}?from={from}&to={to}", data.group4ki1.getId(),
                        monday, monday.plusDays(4))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-cache, public")
                .expectBody()
                .jsonPath("$.owner.label").isEqualTo("4КІ1")
                .jsonPath("$.days.length()").isEqualTo(5)
                .jsonPath("$.days[0].parity").isEqualTo("NUMERATOR")
                .jsonPath("$.days[0].note").isEqualTo("Групи 4КІ навчаються дистанційно")
                .jsonPath("$.days[0].lessons[0].pair").isEqualTo(1)
                .jsonPath("$.days[0].lessons[0].startsAt").isEqualTo("08:30")
                .jsonPath("$.days[0].lessons[0].online").isEqualTo(true)
                .jsonPath("$.days[0].lessons[1].discipline.shortName").isEqualTo("СТП")
                .jsonPath("$.days[0].lessons[1].teacher.shortName").isEqualTo("Кренцін М.Д.")
                .jsonPath("$.days[0].lessons[1].kind").isEqualTo("REGULAR")
                .jsonPath("$.days[2].lessons[0].discipline.shortName").isEqualTo("НДЕКСМ")
                .returnResult();
        String etag = result.getResponseHeaders().getETag();
        assertThat(etag).isNotBlank();

        client.get().uri("/api/public/schedule/group/{id}?from={from}&to={to}", data.group4ki1.getId(), monday,
                        monday.plusDays(4))
                .header(HttpHeaders.IF_NONE_MATCH, etag)
                .exchange()
                .expectStatus().isNotModified();
        client.get().uri("/api/public/schedule/group/{id}?from={day}&to={day}", data.group4ki1.getId(),
                        data.monday(9), data.monday(9))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.days[0].studyDay").isEqualTo(false)
                .jsonPath("$.days[0].note").isEqualTo("День самоврядування")
                .jsonPath("$.days[0].lessons").isEmpty();
    }

    @Test
    void groupCalendarSubscriptionHasEveryScheduledLesson() {
        publication.publish(imports.importTimetable(importCommand()).versionId(), data.semester.getStartsOn(), "admin");

        byte[] ics = client.get().uri("/api/public/ics/group/{id}.ics", data.group4ki1.getId())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("text/calendar;charset=UTF-8")
                .returnResult(byte[].class).getResponseBody();

        String calendar = new String(ics, StandardCharsets.UTF_8);
        assertThat(calendar).startsWith("BEGIN:VCALENDAR\r\n").contains("X-WR-CALNAME:Розклад 4КІ1 — ВТФК");
        assertThat(calendar.split("BEGIN:VEVENT", -1).length - 1).isEqualTo(count(
                "SELECT count(*) FROM lesson WHERE group_id = ? AND status = 'SCHEDULED'", data.group4ki1.getId()));
    }

    @Test
    void appliedSubstitutionIsShownAndSurvivesRepublication() {
        UUID firstVersion = imports.importTimetable(importCommand()).versionId();
        publication.publish(firstVersion, data.semester.getStartsOn(), "admin");
        LocalDate nextMonday = data.monday(4);
        Lesson original = lessonAt(data.group4ki1.getId(), nextMonday, 2);
        UUID replacementId = applySubstitution(original, nextMonday);

        client.get().uri("/api/public/schedule/group/{id}?from={day}&to={day}", data.group4ki1.getId(), nextMonday,
                        nextMonday)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.days[0].lessons.length()").isEqualTo(2)
                .jsonPath("$.days[0].lessons[1].kind").isEqualTo("SUBSTITUTED")
                .jsonPath("$.days[0].lessons[1].teacher.shortName").isEqualTo("Непийвода М.В.")
                .jsonPath("$.days[0].lessons[1].original.teacher.shortName").isEqualTo("Кренцін М.Д.")
                .jsonPath("$.days[0].lessons[1].reasonText").isEqualTo("Відп.");
        client.get().uri("/api/public/schedule/teacher/{id}?from={day}&to={day}", data.krentsin.getId(), nextMonday,
                        nextMonday)
                .exchange()
                .expectBody()
                .jsonPath("$.days[0].lessons[0].kind").isEqualTo("CANCELLED")
                .jsonPath("$.days[0].lessons[0].reasonText").isEqualTo("Відп.");
        assertThat(conflictChecker.preview(List.of(new PlannedLesson(UUID.randomUUID(), nextMonday, 2,
                data.group4ki2.getId(), 0, data.ndeksm.getId(), data.nepyivoda.getId(), null, null)), List.of()))
                .filteredOn(conflict -> conflict.kind() == ConflictKind.TEACHER)
                .singleElement()
                .satisfies(conflict -> assertThat(conflict.date()).isEqualTo(nextMonday));

        UUID secondVersion = versions.create(data.semester.getId(), "Друга редакція", firstVersion).id();
        TemplateLesson ndeksm4ki2 = transaction.execute(status -> em.createQuery(
                        "select t from TemplateLesson t where t.versionId = :v and t.groupId = :g", TemplateLesson.class)
                .setParameter("v", secondVersion).setParameter("g", data.group4ki2.getId()).getSingleResult());
        templateLessons.move(secondVersion, ndeksm4ki2.getId(), new MoveCommand(1, 2, Parity.BOTH, null));
        Queue published = probe(RozkladEvents.TIMETABLE_VERSION_PUBLISHED);
        Queue changed = probe(RozkladEvents.SCHEDULE_CHANGED);

        PublicationResult result = publication.publish(secondVersion, nextMonday, "admin");

        assertThat(result.keptLessons()).isEqualTo(2);
        assertThat(result.coveredByChanges()).isEqualTo(1);
        assertThat(result.conflicts()).singleElement().satisfies(conflict -> {
            assertThat(conflict.kind()).isEqualTo(ConflictKind.TEACHER);
            assertThat(conflict.severity()).isEqualTo(ConflictDto.Severity.HARD);
            assertThat(conflict.date()).isEqualTo(nextMonday);
            assertThat(conflict.lessonIds()).containsExactly(replacementId);
        });
        assertThat(lessonsOf(data.group4ki2.getId(), nextMonday)).as("teacher is busy with the substitution").isZero();
        assertThat(lessonsOf(data.group4ki2.getId(), nextMonday.plusWeeks(1))).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lesson WHERE id = ? AND status = 'SCHEDULED'", replacementId)).isOne();
        assertThat(count("SELECT count(*) FROM lesson WHERE id = ? AND status = 'CANCELLED'", original.getId())).isOne();
        assertThat(count("SELECT count(*) FROM lesson WHERE date = ? AND version_id = ?", data.monday(3), firstVersion))
                .as("lessons before the new version stay").isPositive();
        assertThat(count("SELECT count(*) FROM lesson WHERE date >= ? AND version_id = ? AND origin = 'TEMPLATE' "
                + "AND status = 'SCHEDULED'", nextMonday, firstVersion)).isZero();
        assertThat(versionRepository.findById(firstVersion).orElseThrow().getStatus()).isEqualTo(VersionStatus.ARCHIVED);
        assertThat(versionRepository.findById(secondVersion).orElseThrow().getStatus())
                .isEqualTo(VersionStatus.PUBLISHED);

        assertThat(rabbitTemplate.receiveAndConvert(published.getName(), 15_000))
                .isInstanceOfSatisfying(TimetableVersionPublished.class, event -> {
                    assertThat(event.versionId()).isEqualTo(secondVersion);
                    assertThat(event.effectiveFrom()).isEqualTo(nextMonday);
                    assertThat(event.name()).isEqualTo("Друга редакція");
                });
        assertThat(rabbitTemplate.receiveAndConvert(changed.getName(), 15_000))
                .isInstanceOfSatisfying(ScheduleChanged.class, event -> {
                    assertThat(event.reason()).isEqualTo(ScheduleChangeReason.VERSION_PUBLISHED);
                    assertThat(event.dates()).first().isEqualTo(nextMonday);
                    assertThat(event.groupIds()).contains(data.group4ki1.getId(), data.group4ki2.getId());
                    assertThat(event.teacherIds()).contains(data.krentsin.getId(), data.nepyivoda.getId());
                });
    }

    @Test
    void appliedSwapKeepsBothLessonsAndCancelledChangePinsNothingOnRepublication() {
        UUID firstVersion = imports.importTimetable(importCommand()).versionId();
        publication.publish(firstVersion, data.semester.getStartsOn(), "admin");
        LocalDate swapDay = data.monday(4);
        Lesson firstPair = lessonAt(data.group4ki1.getId(), swapDay, 1);
        Lesson secondPair = lessonAt(data.group4ki1.getId(), swapDay, 2);
        UUID movedId = applySwap(firstPair, secondPair, swapDay);
        LocalDate abandonedDay = data.monday(5);
        Lesson abandoned = lessonAt(data.group4ki1.getId(), abandonedDay, 2);
        UUID cancelledChange = cancelledChangeFor(abandoned, abandonedDay);

        UUID secondVersion = versions.create(data.semester.getId(), "Копія", firstVersion).id();
        PublicationResult result = publication.publish(secondVersion, swapDay, "admin");

        assertThat(result.conflicts()).isEmpty();
        assertThat(count("SELECT count(*) FROM lesson WHERE id IN (?, ?) AND status = 'CANCELLED'", firstPair.getId(),
                secondPair.getId())).as("the swapped lessons stay cancelled").isEqualTo(2);
        assertThat(count("SELECT count(*) FROM lesson WHERE id = ? AND status = 'SCHEDULED'", movedId)).isOne();
        assertThat(lessonsOf(data.group4ki1.getId(), swapDay)).as("only the moved lesson takes place").isOne();
        assertThat(count("SELECT count(*) FROM lesson WHERE id = ?", abandoned.getId()))
                .as("a cancelled change does not keep the old version's lesson").isZero();
        assertThat(count("SELECT count(*) FROM lesson WHERE group_id = ? AND date = ? AND pair_number = 2 "
                        + "AND version_id = ? AND status = 'SCHEDULED'", data.group4ki1.getId(), abandonedDay,
                secondVersion)).isOne();
        assertThat(count("SELECT count(*) FROM change WHERE id = ? AND original_lesson_id IS NULL", cancelledChange))
                .isOne();
        client.get().uri("/api/public/schedule/group/{id}?from={day}&to={day}", data.group4ki1.getId(), swapDay,
                        swapDay)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.days[0].lessons.length()").isEqualTo(1)
                .jsonPath("$.days[0].lessons[0].pair").isEqualTo(1)
                .jsonPath("$.days[0].lessons[0].kind").isEqualTo("MOVED")
                .jsonPath("$.days[0].lessons[0].original.pair").isEqualTo(2);
    }

    @Test
    void draftWithHardConflictsIsNotPublished() {
        List<ImportTimetableCommand.Lesson> lessons = new ArrayList<>(importCommand().lessons());
        lessons.add(new ImportTimetableCommand.Lesson(1, 2, Parity.BOTH, "4КІ2", 0, "КМ", "Кренцін М.Д.", "304",
                false));
        UUID versionId = imports.importTimetable(new ImportTimetableCommand(data.semester.getId(), "З конфліктом",
                lessons)).versionId();

        assertThatThrownBy(() -> publication.publish(versionId, data.semester.getStartsOn(), "admin"))
                .isInstanceOfSatisfying(ConflictException.class, conflict -> assertThat(conflict.getConflicts())
                        .extracting(ConflictDto::kind).contains(ConflictKind.TEACHER));
        assertThat(count("SELECT count(*) FROM lesson")).isZero();
        assertThat(versionRepository.findById(versionId).orElseThrow().getStatus()).isEqualTo(VersionStatus.DRAFT);
    }

    @Test
    void publishedScheduleCannotBeRewrittenRetroactively() {
        UUID firstVersion = imports.importTimetable(importCommand()).versionId();
        publication.publish(firstVersion, data.semester.getStartsOn(), "admin");
        UUID copy = versions.create(data.semester.getId(), "Копія", firstVersion).id();

        assertThatThrownBy(() -> publication.publish(copy, data.semester.getStartsOn(), "admin"))
                .isInstanceOfSatisfying(BusinessRuleException.class, error -> assertThat(error.getCode())
                        .isEqualTo(PublicationService.EFFECTIVE_FROM_IN_PAST));
        assertThatThrownBy(() -> publication.publish(copy, data.semester.getEndsOn().plusDays(1), "admin"))
                .isInstanceOfSatisfying(BusinessRuleException.class, error -> assertThat(error.getCode())
                        .isEqualTo(PublicationService.EFFECTIVE_FROM_OUTSIDE_SEMESTER));
    }

    /** The importer's view of the base timetable; names use an alias, a typo and Latin look-alikes. */
    private ImportTimetableCommand importCommand() {
        return new ImportTimetableCommand(data.semester.getId(), "Розклад І семестру", List.of(
                new ImportTimetableCommand.Lesson(1, 1, Parity.BOTH, "4КІ1", 0, "КМ", null, "304", false),
                new ImportTimetableCommand.Lesson(1, 2, Parity.BOTH, "4KI1", 0, "СТП", "Кренцін М.Д.", "6", false),
                new ImportTimetableCommand.Lesson(3, 3, Parity.NUMERATOR, "4КІ1", 0, "НДЕКСМ", "Непийвода М. В.", "6",
                        false),
                new ImportTimetableCommand.Lesson(1, 3, Parity.BOTH, "4КИ2", 0, "НДЕКСМ", "Непийвода М.В.", "304",
                        false)));
    }

    /** What the substitution module does when an order is published: cancel the original, add the replacement. */
    private UUID applySubstitution(Lesson original, LocalDate date) {
        return transaction.execute(status -> {
            Change change = new Change(ChangeKind.REPLACE_TEACHER, date, 2, 2, data.group4ki1.getId(), 0, "admin");
            change.setOriginal(original.getId(), data.stp.getId(), data.krentsin.getId(), data.lab6.getId());
            change.setReplacement(data.stp.getId(), data.nepyivoda.getId(), data.lab6.getId());
            change.setReason(ReasonCode.ABSENT, null);
            change.setStatus(ChangeStatus.APPLIED);
            em.persist(change);
            em.find(Lesson.class, original.getId()).cancel();
            Lesson replacement = new Lesson(data.semester.getId(), date, 2, data.group4ki1.getId(), 0,
                    data.stp.getId(), LessonOrigin.CHANGE);
            replacement.setTeacherId(data.nepyivoda.getId());
            replacement.setRoom(data.lab6.getId(), true);
            replacement.setChangeId(change.getId());
            em.persist(replacement);
            return replacement.getId();
        });
    }

    /** An applied SWAP: the later pair moves into the earlier slot, both template lessons are cancelled. */
    private UUID applySwap(Lesson original, Lesson moved, LocalDate date) {
        return transaction.execute(status -> {
            Change change = new Change(ChangeKind.SWAP, date, original.getPairNumber(), original.getPairNumber(),
                    data.group4ki1.getId(), 0, "admin");
            change.setOriginal(original.getId(), original.getDisciplineId(), original.getTeacherId(),
                    original.getRoomId());
            change.setSwapLessonId(moved.getId());
            change.setReplacement(moved.getDisciplineId(), moved.getTeacherId(), null);
            change.setReason(ReasonCode.ABSENT, null);
            change.setStatus(ChangeStatus.APPLIED);
            em.persist(change);
            em.find(Lesson.class, original.getId()).cancel();
            em.find(Lesson.class, moved.getId()).cancel();
            Lesson replacement = new Lesson(data.semester.getId(), date, original.getPairNumber(),
                    data.group4ki1.getId(), 0, moved.getDisciplineId(), LessonOrigin.CHANGE);
            replacement.setTeacherId(moved.getTeacherId());
            replacement.setRoom(moved.getRoomId(), true);
            replacement.setChangeId(change.getId());
            em.persist(replacement);
            return replacement.getId();
        });
    }

    /** A decision the dispatcher abandoned: it still names its original lesson. */
    private UUID cancelledChangeFor(Lesson original, LocalDate date) {
        return transaction.execute(status -> {
            Change change = new Change(ChangeKind.CANCEL, date, original.getPairNumber(), original.getPairNumber(),
                    original.getGroupId(), 0, "admin");
            change.setOriginal(original.getId(), original.getDisciplineId(), original.getTeacherId(),
                    original.getRoomId());
            change.setReason(ReasonCode.OTHER, "тест");
            change.setStatus(ChangeStatus.CANCELLED);
            em.persist(change);
            return change.getId();
        });
    }

    private Lesson lessonAt(UUID groupId, LocalDate date, int pair) {
        return transaction.execute(status -> em.createQuery("""
                        select l from Lesson l where l.groupId = :g and l.date = :d and l.pairNumber = :p
                        """, Lesson.class)
                .setParameter("g", groupId).setParameter("d", date).setParameter("p", pair).getSingleResult());
    }

    private int lessonsOf(UUID groupId, LocalDate date) {
        return count("SELECT count(*) FROM lesson WHERE group_id = ? AND date = ? AND status = 'SCHEDULED'", groupId,
                date);
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    private Queue probe(String routingKey) {
        Queue probe = new AnonymousQueue();
        amqpAdmin.declareQueue(probe);
        amqpAdmin.declareBinding(BindingBuilder.bind(probe).to(new TopicExchange(RozkladEvents.EXCHANGE))
                .with(routingKey));
        return probe;
    }
}
