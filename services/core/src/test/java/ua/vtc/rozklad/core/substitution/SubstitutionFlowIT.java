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
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.shared.error.FieldErrorDto;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;
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
import ua.vtc.rozklad.core.substitution.internal.options.Band;
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
    private ChangePublication changePublication;

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
        // потік із запитом на підтвердження (CONFIRM); типовий NOTIFY перевіряє NotifyApprovalIT
        fixture.setting("substitution.approvalMode", "CONFIRM");
        fixture.lesson(semester, MONDAY, 1, ki42, 0, ndks, nepyivoda, room6);
        fixture.lesson(semester, MONDAY, 3, ki42, 0, ndks, nepyivoda, room6);
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

        OptionDto.LessonOptions lessonOptions = this.lessonOptions.of(stpLesson.getId());
        List<OptionDto> options = lessonOptions.options();

        // DOMAIN §7: пару бере інша дисципліна цієї групи її власним викладачем; інший викладач тієї самої
        // дисципліни — лише вручну, тож REPLACE_TEACHER серед варіантів немає
        assertThat(options).extracting(OptionDto::kind).doesNotContain(ChangeKind.REPLACE_TEACHER, ChangeKind.ADDITIONAL);
        assertThat(options.getFirst()).satisfies(best -> {
            assertThat(best.kind()).isEqualTo(ChangeKind.REPLACE_DISCIPLINE);
            assertThat(best.teacher().id()).isEqualTo(nepyivoda.getId());
            assertThat(best.discipline().shortName()).isEqualTo("НДКС");
            assertThat(best.title()).isEqualTo("НДКС — Непийвода М.В.");
            assertThat(best.band()).isEqualTo(Band.CLEAN);
            assertThat(best.reasons()).contains("НДКС відстає від плану", "Закриває вікно в розкладі викладача");
            assertThat(best.room().name()).isEqualTo("6");
            assertThat(best.suggestedRoom().name()).isEqualTo("6");
            assertThat(best.score()).isEqualTo(100);
            // 19.10.2026 уже минуло: варіанти є, щоб записати, що сталося, але без «Рекомендовано»
            assertThat(best.recommended()).isFalse();
        });
        assertThat(lessonOptions.past()).isTrue();
        assertThat(lessonOptions.declined()).isEmpty();
        assertThat(options).filteredOn(option -> option.kind() == ChangeKind.REPLACE_DISCIPLINE
                        && option.teacher().id().equals(hanza.getId())).singleElement()
                .satisfies(option -> {
                    assertThat(option.band()).isEqualTo(Band.CAUTION);
                    assertThat(option.warnings())
                            .containsExactly("Вже має пару з цією групою (1 пара) — краще перестановка");
                });
        assertThat(options).filteredOn(option -> option.kind() == ChangeKind.CANCEL).singleElement()
                .satisfies(cancel -> {
                    assertThat(cancel.reasons()).contains("Остання пара: група піде раніше");
                    assertThat(cancel.band()).isEqualTo(Band.LAST);
                });
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

    /** DOMAIN §7: a substitute must not be absent; an absence registered after the request blocks confirming. */
    @Test
    void substituteRegisteredAbsentCannotConfirm() {
        ChangeDto change = replaceWithHanza();

        AbsenceDto hanzaAbsent = reportWholeDay(hanza);

        assertThatThrownBy(() -> replies.replyFromBot(change.id(), hanza.getId(), ConfirmationDecision.CONFIRM, null))
                .isInstanceOfSatisfying(ConflictException.class, conflict -> assertThat(conflict.getConflicts())
                        .singleElement().satisfies(found -> {
                            assertThat(found.kind()).isEqualTo(ConflictKind.UNAVAILABLE);
                            assertThat(found.pair()).isEqualTo(2);
                        }));
        assertThatThrownBy(() -> changeCommands.confirm(change.id(), "admin")).isInstanceOf(ConflictException.class);
        assertThat(affectedLessons.of(hanzaAbsent.id()))
                .filteredOn(AffectedLessonDto::asSubstitute).singleElement().satisfies(item -> {
                    assertThat(item.lesson().id()).isEqualTo(stpLesson.getId());
                    assertThat(item.decision().changeId()).isEqualTo(change.id());
                    assertThat(item.decision().status()).isEqualTo(ChangeStatus.PENDING_CONFIRMATION);
                });
    }

    /**
     * A confirmed substitute who is registered absent afterwards: the absence lists the lesson, the
     * dashboard summary names the change and publication is refused with an UNAVAILABLE conflict.
     */
    @Test
    void confirmedSubstituteRegisteredAbsentIsSurfacedAndBlocksPublication() {
        ChangeDto change = replaceWithHanza();
        replies.replyFromBot(change.id(), hanza.getId(), ConfirmationDecision.CONFIRM, null);

        AbsenceDto hanzaAbsent = reportWholeDay(hanza);

        List<AffectedLessonDto> affected = affectedLessons.of(hanzaAbsent.id());
        assertThat(affected).extracting(item -> item.lesson().pair()).containsExactly(1, 2);
        assertThat(affected.get(0).asSubstitute()).isFalse();
        assertThat(affected.get(1)).satisfies(item -> {
            assertThat(item.asSubstitute()).isTrue();
            assertThat(item.lesson().id()).isEqualTo(stpLesson.getId());
            assertThat(item.decision().status()).isEqualTo(ChangeStatus.CONFIRMED);
        });
        assertThat(overview.summarize(MONDAY).absentSubstitutes()).singleElement().satisfies(absent -> {
            assertThat(absent.changeId()).isEqualTo(change.id());
            assertThat(absent.groupCode()).isEqualTo("4КІ1");
            assertThat(absent.pair()).isEqualTo(2);
            assertThat(absent.teacherName()).isEqualTo("Ганза І.П.");
        });
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                changePublication.apply(List.of(change.id()), UUID.randomUUID(), "admin")))
                .isInstanceOfSatisfying(ConflictException.class, conflict -> assertThat(conflict.getConflicts())
                        .extracting(ConflictDto::kind).containsExactly(ConflictKind.UNAVAILABLE));
        assertThat(changes.findById(change.id()).orElseThrow().getStatus()).isEqualTo(ChangeStatus.CONFIRMED);
    }

    /** An unknown id in the request body names its field (400), it is not a 404 of the whole request. */
    @Test
    void unknownIdsInTheBodyAreFieldErrors() {
        UUID unknown = UUID.fromString("00000000-0000-4000-8000-000000000000");

        assertThatThrownBy(() -> absences.report(unknown, new AbsenceRequests.AbsenceData(MONDAY, MONDAY, null,
                AbsenceReasonCategory.SICK_LEAVE, null), AbsenceSource.ADMIN, "admin"))
                .isInstanceOfSatisfying(FieldValidationException.class, error -> assertThat(error.getErrors())
                        .extracting(FieldErrorDto::field).containsExactly("teacherId"));
        assertThatThrownBy(() -> changeCommands.create(new CreateChangeRequest(ChangeKind.REPLACE_TEACHER, MONDAY, 2,
                null, ki41.getId(), null, stpLesson.getId(), null, unknown, null, null, ReasonCode.OTHER, null, null),
                "admin"))
                .isInstanceOfSatisfying(FieldValidationException.class, error -> assertThat(error.getErrors())
                        .extracting(FieldErrorDto::field).containsExactly("newTeacherId"));
        assertThatThrownBy(() -> changeCommands.create(new CreateChangeRequest(ChangeKind.CANCEL, MONDAY, 2, null,
                unknown, null, stpLesson.getId(), null, null, null, null, ReasonCode.OTHER, null, null), "admin"))
                .isInstanceOfSatisfying(FieldValidationException.class, error -> assertThat(error.getErrors())
                        .extracting(FieldErrorDto::field).containsExactly("groupId"));
    }

    private ChangeDto replaceWithHanza() {
        return changeCommands.create(new CreateChangeRequest(ChangeKind.REPLACE_TEACHER, MONDAY, 2, null,
                ki41.getId(), null, stpLesson.getId(), null, hanza.getId(), null, null, ReasonCode.OTHER, "тест",
                null), "admin");
    }

    private AbsenceDto reportWholeDay(Teacher teacher) {
        return absences.report(teacher.getId(), new AbsenceRequests.AbsenceData(MONDAY, MONDAY, null,
                AbsenceReasonCategory.SICK_LEAVE, null), AbsenceSource.ADMIN, "admin");
    }

    private AbsenceDto reportAbsence() {
        return absences.report(krentsin.getId(), new AbsenceRequests.AbsenceData(MONDAY, MONDAY, List.of(2),
                AbsenceReasonCategory.SICK_LEAVE, "Лікарняний"), AbsenceSource.ADMIN, "admin");
    }
}
