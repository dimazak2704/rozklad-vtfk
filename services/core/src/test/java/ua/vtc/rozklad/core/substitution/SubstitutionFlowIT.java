package ua.vtc.rozklad.core.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.contracts.AbsenceReasonCategory;
import ua.vtc.rozklad.contracts.AbsenceSource;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.contracts.events.AbsenceReported;
import ua.vtc.rozklad.contracts.events.RozkladEvents;
import ua.vtc.rozklad.core.accounting.AccountingStatus;
import ua.vtc.rozklad.core.accounting.TeacherActivity;
import ua.vtc.rozklad.core.accounting.TeacherActivityProvider;
import ua.vtc.rozklad.core.accounting.internal.AccountingRow;
import ua.vtc.rozklad.core.accounting.internal.AccountingService;
import ua.vtc.rozklad.core.accounting.internal.LedgerResult;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.shared.domain.ReasonCode;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.substitution.fixture.EventProbe;
import ua.vtc.rozklad.core.substitution.fixture.ScheduleFixture;
import ua.vtc.rozklad.core.substitution.internal.absence.AbsenceDto;
import ua.vtc.rozklad.core.substitution.internal.absence.AbsenceRequests;
import ua.vtc.rozklad.core.substitution.internal.absence.AbsenceService;
import ua.vtc.rozklad.core.substitution.internal.absence.AffectedLessonDto;
import ua.vtc.rozklad.core.substitution.internal.absence.AffectedLessons;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeCommands;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeDto;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeReplies;
import ua.vtc.rozklad.core.substitution.internal.change.CreateChangeRequest;
import ua.vtc.rozklad.core.substitution.internal.options.LessonOptions;
import ua.vtc.rozklad.core.substitution.internal.options.OptionDto;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.timetable.Lesson;

/**
 * A teacher's absence from report to substitution on real data (DOMAIN §7, §9): affected lessons,
 * ranked options, coverage, accounting and the teacher statistics.
 */
@CoreIntegrationTest
class SubstitutionFlowIT {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);
    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    private AbsenceService absences;

    @Autowired
    private AffectedLessons affectedLessons;

    @Autowired
    private LessonOptions lessonOptions;

    @Autowired
    private ChangeCommands changeCommands;

    @Autowired
    private ChangeReplies replies;

    @Autowired
    private ChangeRepository changes;

    @Autowired
    private SubstitutionOverview overview;

    @Autowired
    private AccountingService accounting;

    @Autowired
    private TeacherActivityProvider teacherActivity;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private ScheduleFixture fixture;
    private Semester semester;
    private StudentGroup ki41;
    private Teacher krentsin;
    private Teacher nepyivoda;
    private Teacher hanza;
    private Lesson stpLesson;

    @BeforeEach
    void collegeDay() {
        fixture = new ScheduleFixture(transaction, entityManager, jdbc);
        semester = fixture.semester();
        ki41 = fixture.group("4КІ1", 4);
        StudentGroup ki42 = fixture.group("4КІ2", 4);
        UUID computerEngineering = fixture.cycleCommission("ЦК комп'ютерної інженерії");
        krentsin = fixture.teacher("Кренцін М.Д.", "Кренцін", null, computerEngineering);
        nepyivoda = fixture.teacher("Непийвода М.В.", "Непийвода", null, computerEngineering);
        hanza = fixture.teacher("Ганза І.П.", "Ганза");
        Discipline stp = fixture.discipline("СТП", "Сучасні технології проєктування");
        Discipline ndks = fixture.discipline("НДКС", "Надійність, діагностика та експлуатація КС і мереж");
        Discipline opcz = fixture.discipline("ОПЦЗ", "Охорона праці та цивільний захист");
        Room room6 = fixture.room("6");
        Room room110 = fixture.room("110");

        fixture.lesson(semester, MONDAY, 1, ki41, 0, opcz, hanza, room110);
        stpLesson = fixture.lesson(semester, MONDAY, 2, ki41, 0, stp, krentsin, room6);
        fixture.lesson(semester, MONDAY, 3, ki41, 0, ndks, nepyivoda, room6);
        fixture.lesson(semester, MONDAY, 1, ki42, 0, ndks, nepyivoda, room6);
        fixture.competence(nepyivoda, stp);
        fixture.load(semester, ki41, stp, 44, krentsin);
        fixture.load(semester, ki41, ndks, 40, nepyivoda);
        fixture.load(semester, ki41, opcz, 30, hanza);
    }

    @AfterEach
    void cleanUp() {
        fixture.cleanUp();
    }

    @Test
    void absenceShowsItsLessonsAndRankedOptions() {
        EventProbe probe = EventProbe.listen(amqpAdmin, rabbitTemplate, RozkladEvents.ABSENCE_REPORTED);

        AbsenceDto absence = reportAbsence();

        assertThat(probe.await(AbsenceReported.class, event -> event.absenceId().equals(absence.id()), 1, WAIT))
                .singleElement().satisfies(event -> {
                    assertThat(event.teacherName()).isEqualTo("Кренцін М.Д.");
                    assertThat(event.pairs()).containsExactly(2);
                    assertThat(event.source()).isEqualTo(AbsenceSource.ADMIN);
                });
        assertThat(affectedLessons.of(absence.id())).singleElement().satisfies(affected -> {
            assertThat(affected.date()).isEqualTo(MONDAY);
            assertThat(affected.lesson().id()).isEqualTo(stpLesson.getId());
            assertThat(affected.lesson().startsAt()).hasToString("09:40");
            assertThat(affected.decision()).isNull();
        });
        assertThat(overview.summarize(MONDAY).uncoveredLessons()).isEqualTo(1);

        List<OptionDto> options = lessonOptions.of(stpLesson.getId()).options();

        assertThat(options.getFirst()).satisfies(best -> {
            assertThat(best.kind()).isEqualTo(ChangeKind.REPLACE_TEACHER);
            assertThat(best.teacher().id()).isEqualTo(nepyivoda.getId());
            assertThat(best.score()).isEqualTo(95);
            assertThat(best.reasons()).containsExactly("Веде цю дисципліну", "Та сама циклова комісія",
                    "Закриває вікно між 1 і 3 парою", "Вже має 2 пари цього дня");
            assertThat(best.room().name()).isEqualTo("6");
        });
        assertThat(options).filteredOn(option -> option.kind() == ChangeKind.REPLACE_DISCIPLINE)
                .extracting(OptionDto::title)
                .contains("Замінити на НДКС (Непийвода М.В.)", "Замінити на ОПЦЗ (Ганза І.П.)");
        assertThat(options).filteredOn(option -> option.kind() == ChangeKind.SWAP).singleElement()
                .satisfies(swap -> assertThat(swap.title()).isEqualTo("Переставити 3 пару на 2: група піде раніше"));
        assertThat(options).filteredOn(option -> option.kind() == ChangeKind.CANCEL).singleElement()
                .satisfies(cancel -> assertThat(cancel.warnings()).containsExactly("У групи з'явиться вікно"));
    }

    @Test
    void decisionCoversTheLessonAndCountsInTheStatistics() {
        AbsenceDto absence = reportAbsence();
        ChangeDto change = changeCommands.create(new CreateChangeRequest(ChangeKind.REPLACE_TEACHER, MONDAY, 2, null,
                ki41.getId(), null, stpLesson.getId(), null, nepyivoda.getId(), null, null, ReasonCode.ABSENT, null,
                absence.id()), "admin");

        List<AffectedLessonDto> affected = affectedLessons.of(absence.id());
        assertThat(affected.getFirst().decision().status()).isEqualTo(ChangeStatus.PENDING_CONFIRMATION);
        assertThat(overview.summarize(MONDAY).uncoveredLessons()).isZero();
        assertThat(overview.summarize(MONDAY).pending()).isEqualTo(1);

        replies.replyFromBot(change.id(), nepyivoda.getId(), ConfirmationDecision.CONFIRM, null);

        List<TeacherActivity> october = teacherActivity.activityBetween(LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31));
        assertThat(october).filteredOn(row -> row.teacherId().equals(nepyivoda.getId())).singleElement()
                .satisfies(row -> {
                    assertThat(row.substitutions()).isEqualTo(1);
                    assertThat(row.substitutedPairs()).isEqualTo(1);
                });
        assertThat(october).filteredOn(row -> row.teacherId().equals(krentsin.getId())).singleElement()
                .satisfies(row -> assertThat(row.missedPairs()).isEqualTo(1));
    }

    @Test
    void cancelledAbsenceCancelsItsOpenChanges() {
        AbsenceDto absence = reportAbsence();
        ChangeDto change = changeCommands.create(new CreateChangeRequest(ChangeKind.CANCEL, MONDAY, 2, null,
                ki41.getId(), null, stpLesson.getId(), null, null, null, null, ReasonCode.ABSENT, null, absence.id()),
                "admin");

        absences.cancel(absence.id(), "admin");

        assertThat(changes.findById(change.id()).orElseThrow().getStatus()).isEqualTo(ChangeStatus.CANCELLED);
        assertThat(overview.summarize(MONDAY).absences()).isZero();
    }

    @Test
    void overlappingAbsenceOfTheSameTeacherIsRejected() {
        reportAbsence();

        assertThatThrownBy(() -> absences.report(krentsin.getId(), new AbsenceRequests.AbsenceData(MONDAY,
                MONDAY.plusDays(1), null, AbsenceReasonCategory.BUSINESS_TRIP, null), AbsenceSource.ADMIN, "admin"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("перетинається");
    }

    @Test
    void accountingComparesPlanWithTheLessons() {
        List<AccountingRow> rows = accounting.rows(semester.getId(), ki41.getId());

        assertThat(rows).hasSize(3);
        LedgerResult stp = rows.stream().filter(row -> row.disciplineId().equals(stpLesson.getDisciplineId()))
                .findFirst().orElseThrow().ledger();
        assertThat(stp.plan()).isEqualTo(44);
        // заняття 19.10.2026 уже минуло: 1 пара = 2 год
        assertThat(stp.fact()).isEqualTo(2);
        assertThat(stp.remaining()).isZero();
        assertThat(stp.status()).isEqualTo(AccountingStatus.WILL_UNDERRUN);
        assertThat(stp.suggestion().text()).isEqualTo("Не вистачає 42 год: додати 21 пару");
        assertThat(accounting.progressOfGroup(semester.getId(), ki41.getId()).get(stpLesson.getDisciplineId())
                .hoursBehind()).isEqualTo(42);
    }

    private AbsenceDto reportAbsence() {
        return absences.report(krentsin.getId(), new AbsenceRequests.AbsenceData(MONDAY, MONDAY, List.of(2),
                AbsenceReasonCategory.SICK_LEAVE, "Лікарняний"), AbsenceSource.ADMIN, "admin");
    }
}
