package ua.vtc.rozklad.core.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.hibernate.StaleStateException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.contracts.AbsenceReasonCategory;
import ua.vtc.rozklad.contracts.AbsenceSource;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.contracts.events.RozkladEvents;
import ua.vtc.rozklad.contracts.events.SubstitutionConfirmationRequested;
import ua.vtc.rozklad.contracts.events.SubstitutionStatusChanged;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.settings.SystemSettings;
import ua.vtc.rozklad.core.shared.domain.ReasonCode;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.shared.error.FieldErrorDto;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;
import ua.vtc.rozklad.core.shared.security.CurrentUser;
import ua.vtc.rozklad.core.substitution.fixture.EventProbe;
import ua.vtc.rozklad.core.substitution.fixture.Parallel;
import ua.vtc.rozklad.core.substitution.fixture.ScheduleFixture;
import ua.vtc.rozklad.core.substitution.internal.absence.AbsenceDto;
import ua.vtc.rozklad.core.substitution.internal.absence.AbsenceRequests;
import ua.vtc.rozklad.core.substitution.internal.absence.AbsenceService;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeCommands;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeDto;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeReplies;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeStatusMachine;
import ua.vtc.rozklad.core.substitution.internal.change.ConfirmationReminders;
import ua.vtc.rozklad.core.substitution.internal.change.CreateChangeRequest;
import ua.vtc.rozklad.core.substitution.internal.change.UpdateChangeRequest;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.timetable.Lesson;

/**
 * Change lifecycle on real PostgreSQL and RabbitMQ (DOMAIN §7): approval rules, the teacher's
 * answer, ownership, illegal transitions, history, optimistic locking, events through the outbox
 * and the idempotent reminder.
 */
@CoreIntegrationTest
class ChangeLifecycleIT {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);
    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    private ChangeCommands commands;

    @Autowired
    private ChangeReplies replies;

    @Autowired
    private AbsenceService absences;

    @Autowired
    private ConfirmationReminders reminders;

    @Autowired
    private ChangeRepository changes;

    @Autowired
    private SubstitutionOverview overview;

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

    @Autowired
    private Clock clock;

    private ScheduleFixture fixture;
    private Semester semester;
    private StudentGroup group;
    private Teacher krentsin;
    private Teacher nepyivoda;
    private Discipline stp;
    private Room room;
    private Lesson lesson;

    @BeforeEach
    void schedule() {
        fixture = new ScheduleFixture(transaction, entityManager, jdbc);
        semester = fixture.semester();
        group = fixture.group("4КІ1", 4);
        krentsin = fixture.teacher("Кренцін М.Д.", "Кренцін", TestJwts.KRENTSIN_ID, null);
        nepyivoda = fixture.teacher("Непийвода М.В.", "Непийвода", TestJwts.NEPYIVODA_ID, null);
        stp = fixture.discipline("СТП", "Сучасні технології проєктування");
        room = fixture.room("6");
        lesson = fixture.lesson(semester, MONDAY, 2, group, 0, stp, krentsin, room);
        // ці тести перевіряють режим CONFIRM; NOTIFY (типовий) — у NotifyApprovalIT
        fixture.setting("substitution.approvalMode", "CONFIRM");
    }

    @AfterEach
    void cleanUp() {
        fixture.cleanUp();
    }

    @Test
    void replacementWaitsForTheTeacherThenIsConfirmed() {
        EventProbe probe = EventProbe.listen(amqpAdmin, rabbitTemplate,
                RozkladEvents.SUBSTITUTION_CONFIRMATION_REQUESTED, RozkladEvents.SUBSTITUTION_STATUS_CHANGED);
        Instant before = clock.instant();

        ChangeDto created = commands.create(replaceTeacher(nepyivoda), "admin");

        assertThat(created.status()).isEqualTo(ChangeStatus.PENDING_CONFIRMATION);
        assertThat(created.approvalMode()).isEqualTo(ApprovalMode.CONFIRM);
        // термін — з налаштувань (substitution.confirmTimeoutMinutes, типово 120 хв, V1_802)
        Duration timeout = SystemSettings.DEFAULTS.confirmTimeout();
        assertThat(created.confirmDeadline()).isBetween(before.plus(timeout), clock.instant().plus(timeout));
        assertThat(created.history()).singleElement().satisfies(entry -> {
            assertThat(entry.from()).isNull();
            assertThat(entry.to()).isEqualTo(ChangeStatus.PENDING_CONFIRMATION);
            assertThat(entry.actor()).isEqualTo("admin");
        });
        assertThat(created.printedReason()).isEqualTo("Відп.");
        assertThat(probe.await(SubstitutionConfirmationRequested.class,
                event -> event.changeId().equals(created.id()), 1, WAIT)).singleElement().satisfies(event -> {
                    assertThat(event.teacherId()).isEqualTo(nepyivoda.getId());
                    assertThat(event.groupCode()).isEqualTo("4КІ1");
                    assertThat(event.pair()).isEqualTo(2);
                    assertThat(event.startsAt()).isEqualTo(LocalTime.of(9, 40));
                    assertThat(event.endsAt()).isEqualTo(LocalTime.of(10, 40));
                    assertThat(event.disciplineName()).isEqualTo("Сучасні технології проєктування");
                    assertThat(event.originalTeacherName()).isEqualTo("Кренцін М.Д.");
                    assertThat(event.roomName()).isEqualTo("6");
                    assertThat(event.deadline().truncatedTo(ChronoUnit.MILLIS))
                            .isEqualTo(created.confirmDeadline().truncatedTo(ChronoUnit.MILLIS));
                });

        ChangeDto confirmed = replies.replyAsUser(created.id(), user(nepyivoda), ConfirmationDecision.CONFIRM, null);

        assertThat(confirmed.status()).isEqualTo(ChangeStatus.CONFIRMED);
        assertThat(confirmed.respondedAt()).isNotNull();
        assertThat(confirmed.history()).extracting(ChangeDto.HistoryEntry::to)
                .containsExactly(ChangeStatus.PENDING_CONFIRMATION, ChangeStatus.CONFIRMED);
        assertThat(confirmed.history().getLast().actor()).isEqualTo("nepyivoda");
        assertThat(probe.await(SubstitutionStatusChanged.class, event -> event.changeId().equals(created.id())
                && event.status() == ChangeStatus.CONFIRMED, 1, WAIT)).hasSize(1);
    }

    @Test
    void onlyTheAssignedTeacherMayAnswer() {
        ChangeDto created = commands.create(replaceTeacher(nepyivoda), "admin");

        assertThatThrownBy(() -> replies.replyAsUser(created.id(), user(krentsin), ConfirmationDecision.CONFIRM, null))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> replies.replyFromBot(created.id(), krentsin.getId(), ConfirmationDecision.DECLINE,
                null)).isInstanceOf(AccessDeniedException.class);
        assertThat(changes.findById(created.id()).orElseThrow().getStatus())
                .isEqualTo(ChangeStatus.PENDING_CONFIRMATION);
    }

    @Test
    void answerToAChangeThatNoLongerWaitsIsAConflict() {
        ChangeDto created = commands.create(replaceTeacher(nepyivoda), "admin");
        commands.cancel(created.id(), "admin");

        assertThatThrownBy(() -> replies.replyFromBot(created.id(), nepyivoda.getId(), ConfirmationDecision.CONFIRM,
                null)).isInstanceOf(ConflictException.class).hasMessageContaining("Скасовано");
    }

    @Test
    void illegalTransitionsAreRejectedAndCancellingIsIdempotent() {
        ChangeDto created = commands.create(replaceTeacher(nepyivoda), "admin");
        commands.confirm(created.id(), "admin");

        assertThatThrownBy(() -> commands.confirm(created.id(), "admin")).isInstanceOf(BusinessRuleException.class)
                .extracting(error -> ((BusinessRuleException) error).getCode())
                .isEqualTo(ChangeStatusMachine.ILLEGAL_TRANSITION);
        assertThatThrownBy(() -> commands.requestConfirmation(created.id(), "admin"))
                .isInstanceOf(BusinessRuleException.class);

        commands.cancel(created.id(), "admin");
        commands.cancel(created.id(), "admin");

        assertThat(changes.findById(created.id()).orElseThrow().getStatus()).isEqualTo(ChangeStatus.CANCELLED);
        long current = changes.findById(created.id()).orElseThrow().getVersion();
        assertThatThrownBy(() -> commands.update(created.id(),
                new UpdateChangeRequest(null, ReasonCode.ABSENT, null, current), "admin"))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        error -> assertThat(error.getCode()).isEqualTo("CHANGE_NOT_EDITABLE"));
        assertThatThrownBy(() -> commands.confirm(created.id(), "admin")).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void declinedChangeNeedsADecisionAndCanBeAskedAgain() {
        ChangeDto created = commands.create(replaceTeacher(nepyivoda), "admin");

        ChangeDto declined = replies.replyFromBot(created.id(), nepyivoda.getId(), ConfirmationDecision.DECLINE,
                "Маю пару в іншій групі");

        assertThat(declined.status()).isEqualTo(ChangeStatus.DECLINED);
        assertThat(declined.responseComment()).isEqualTo("Маю пару в іншій групі");
        assertThat(declined.history().getLast().actor()).isEqualTo("Непийвода М.В. (Telegram)");
        assertThat(overview.summarize(MONDAY).declined()).isEqualTo(1);
        assertThat(overview.summarize(MONDAY).declinedChanges()).singleElement()
                .satisfies(change -> assertThat(change.groupCode()).isEqualTo("4КІ1"));

        ChangeDto askedAgain = commands.requestConfirmation(created.id(), "admin");

        assertThat(askedAgain.status()).isEqualTo(ChangeStatus.PENDING_CONFIRMATION);
        assertThat(askedAgain.remindedAt()).isNull();
        assertThat(askedAgain.history()).extracting(ChangeDto.HistoryEntry::to).containsExactly(
                ChangeStatus.PENDING_CONFIRMATION, ChangeStatus.DECLINED, ChangeStatus.PENDING_CONFIRMATION);
        assertThat(overview.summarize(MONDAY).declined()).isZero();
    }

    @Test
    void autoApprovalConfirmsAtOnce() {
        fixture.setting("substitution.approvalMode", "AUTO");

        ChangeDto created = commands.create(replaceTeacher(nepyivoda), "admin");

        assertThat(created.status()).isEqualTo(ChangeStatus.CONFIRMED);
        assertThat(created.approvalMode()).isEqualTo(ApprovalMode.AUTO);
        assertThat(created.confirmDeadline()).isNull();
    }

    @Test
    void cancellationNeedsNoTeacherAndIsConfirmedAtOnce() {
        ChangeDto cancel = commands.create(new CreateChangeRequest(ChangeKind.CANCEL, MONDAY, 2, null, group.getId(),
                null, lesson.getId(), null, null, null, null, ReasonCode.ABSENT, null, null), "admin");

        assertThat(cancel.status()).isEqualTo(ChangeStatus.CONFIRMED);
        assertThat(cancel.approvalMode()).isEqualTo(ApprovalMode.AUTO);
    }

    @Test
    void busyTeacherCannotBeAssigned() {
        StudentGroup other = fixture.group("4КІ2", 4);
        Lesson busy = fixture.lesson(semester, MONDAY, 2, other, 0, fixture.discipline("НДКС", "НДЕКСМ"), nepyivoda,
                fixture.room("110"));

        assertThatThrownBy(() -> commands.create(replaceTeacher(nepyivoda), "admin"))
                .isInstanceOfSatisfying(ConflictException.class, conflict -> assertThat(conflict.getConflicts())
                        .singleElement().satisfies(found -> {
                            assertThat(found.kind()).isEqualTo(ConflictKind.TEACHER);
                            assertThat(found.severity()).isEqualTo(ConflictDto.Severity.HARD);
                            assertThat(found.lessonIds()).containsExactly(busy.getId());
                            assertThat(found.message()).isEqualTo("Непийвода М.В. уже має 2 пару: група 4КІ2 (НДКС)");
                        }));
        assertThat(changes.count()).isZero();
    }

    @Test
    void absentTeacherCannotBeTheSubstitute() {
        absences.report(nepyivoda.getId(), new AbsenceRequests.AbsenceData(MONDAY, MONDAY, List.of(2),
                AbsenceReasonCategory.OTHER, null), AbsenceSource.ADMIN, "admin");

        assertThatThrownBy(() -> commands.create(replaceTeacher(nepyivoda), "admin"))
                .isInstanceOfSatisfying(ConflictException.class, conflict -> assertThat(conflict.getConflicts())
                        .singleElement().satisfies(found -> {
                            assertThat(found.kind()).isEqualTo(ConflictKind.UNAVAILABLE);
                            assertThat(found.severity()).isEqualTo(ConflictDto.Severity.HARD);
                            assertThat(found.message()).isEqualTo("Непийвода М.В. відсутній 19.10.2026 на 2 парі");
                        }));
        assertThat(changes.count()).isZero();
    }

    @Test
    void absenceAtAnotherPairDoesNotBlockTheSubstitute() {
        absences.report(nepyivoda.getId(), new AbsenceRequests.AbsenceData(MONDAY, MONDAY, List.of(5),
                AbsenceReasonCategory.OTHER, null), AbsenceSource.ADMIN, "admin");

        assertThat(commands.create(replaceTeacher(nepyivoda), "admin").status())
                .isEqualTo(ChangeStatus.PENDING_CONFIRMATION);
    }

    @Test
    void parallelRequestsCannotPromiseOneTeacherToTwoGroups() throws Exception {
        StudentGroup other = fixture.group("4КІ2", 4);
        Lesson otherLesson = fixture.lesson(semester, MONDAY, 2, other, 0, stp, fixture.teacher("Стець О.В.", "Стець"),
                fixture.room("110"));
        CreateChangeRequest forOther = new CreateChangeRequest(ChangeKind.REPLACE_TEACHER, MONDAY, 2, null,
                other.getId(), null, otherLesson.getId(), null, nepyivoda.getId(), null, null, ReasonCode.ABSENT,
                null, null);

        List<Object> results = Parallel.run(() -> commands.create(replaceTeacher(nepyivoda), "admin"),
                () -> commands.create(forOther, "admin"));

        assertThat(results).filteredOn(ChangeDto.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(ConflictException.class::isInstance).singleElement()
                .satisfies(error -> assertThat(((ConflictException) error).getConflicts())
                        .extracting(ConflictDto::kind).containsExactly(ConflictKind.TEACHER));
        assertThat(changes.count()).isOne();
    }

    @Test
    void parallelAbsencesOfOneTeacherDoNotOverlap() throws Exception {
        AbsenceRequests.AbsenceData sameDay = new AbsenceRequests.AbsenceData(MONDAY, MONDAY, List.of(8),
                AbsenceReasonCategory.OTHER, null);

        List<Object> results = Parallel.run(
                () -> absences.report(krentsin.getId(), sameDay, AbsenceSource.ADMIN, "admin"),
                () -> absences.report(krentsin.getId(), sameDay, AbsenceSource.ADMIN, "admin"),
                () -> absences.report(krentsin.getId(), sameDay, AbsenceSource.ADMIN, "admin"));

        assertThat(results).filteredOn(AbsenceDto.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(ConflictException.class::isInstance).hasSize(2)
                .allSatisfy(error -> assertThat(((ConflictException) error).getMessage())
                        .startsWith("Відсутність перетинається з уже зареєстрованою"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM absence WHERE status = 'ACTIVE'", Integer.class))
                .isOne();
    }

    @Test
    void additionalPairNamesOnlyASubgroupTheGroupHas() {
        fixture.lesson(semester, MONDAY.plusDays(1), 3, group, 1, fixture.discipline("Ін.мова", "Іноземна мова"),
                nepyivoda, fixture.room("110"));

        assertThatThrownBy(() -> commands.create(additional(7), "admin"))
                .isInstanceOfSatisfying(FieldValidationException.class, error -> assertThat(error.getErrors())
                        .containsExactly(new FieldErrorDto("subgroup", "У групи 4КІ1 немає підгрупи 7.")));
        assertThat(commands.create(additional(1), "admin").subgroup()).isEqualTo(1);
    }

    @Test
    void roomOfAGroupStudyingOnlineIsFree() {
        // розпорядження №35: 1ОП2 отримує 403, бо 3ОБ у цей час навчається дистанційно
        Room room403 = fixture.room("403");
        StudentGroup online = fixture.group("3ОБ", 3);
        Lesson remote = fixture.lesson(semester, MONDAY, 4, online, 0, fixture.discipline("ФЕА", "ФЕА"), krentsin,
                room403);
        jdbc.update("UPDATE lesson SET online = true, room_exclusive = false WHERE id = ?", remote.getId());

        ChangeDto additional = commands.create(new CreateChangeRequest(ChangeKind.ADDITIONAL, MONDAY, 4, null,
                group.getId(), null, null, stp.getId(), nepyivoda.getId(), room403.getId(), null, ReasonCode.OTHER,
                null, null), "admin");
        assertThat(additional.status()).isEqualTo(ChangeStatus.PENDING_CONFIRMATION);

        jdbc.update("UPDATE lesson SET online = false, room_exclusive = true WHERE id = ?", remote.getId());
        StudentGroup third = fixture.group("4КІ3", 4);
        assertThatThrownBy(() -> commands.create(new CreateChangeRequest(ChangeKind.ADDITIONAL, MONDAY, 4, null,
                third.getId(), null, null, stp.getId(), null, room403.getId(), null, ReasonCode.OTHER, null, null),
                "admin"))
                .isInstanceOfSatisfying(ConflictException.class, conflict -> assertThat(conflict.getConflicts())
                        .extracting(ConflictDto::kind).contains(ConflictKind.ROOM));
    }

    @Test
    void oneOpenDecisionPerLesson() {
        commands.create(replaceTeacher(nepyivoda), "admin");

        assertThatThrownBy(() -> commands.create(new CreateChangeRequest(ChangeKind.CANCEL, MONDAY, 2, null,
                group.getId(), null, lesson.getId(), null, null, null, null, ReasonCode.ABSENT, null, null), "admin"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("вже є рішення");
    }

    @Test
    void declinedChangeCannotBeAskedAgainOnceTheLessonHasAnotherDecision() {
        ChangeDto declined = commands.create(replaceTeacher(nepyivoda), "admin");
        replies.replyFromBot(declined.id(), nepyivoda.getId(), ConfirmationDecision.DECLINE, null);
        ChangeDto cancel = commands.create(new CreateChangeRequest(ChangeKind.CANCEL, MONDAY, 2, null,
                group.getId(), null, lesson.getId(), null, null, null, null, ReasonCode.ABSENT, null, null), "admin");
        assertThat(cancel.status()).isEqualTo(ChangeStatus.CONFIRMED);

        assertThatThrownBy(() -> commands.requestConfirmation(declined.id(), "admin"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("вже є рішення");
        assertThat(changes.findById(declined.id()).orElseThrow().getStatus()).isEqualTo(ChangeStatus.DECLINED);

        commands.cancel(cancel.id(), "admin");
        assertThat(commands.requestConfirmation(declined.id(), "admin").status())
                .isEqualTo(ChangeStatus.PENDING_CONFIRMATION);
    }

    @Test
    void teacherReportsOnlyFromTodayOnTheDispatcherAlsoThePast() {
        LocalDate today = LocalDate.now(clock);
        AbsenceRequests.AbsenceData backdated = new AbsenceRequests.AbsenceData(today.minusDays(20), today, null,
                AbsenceReasonCategory.SICK_LEAVE, null);

        for (AbsenceSource teacherSource : List.of(AbsenceSource.WEB, AbsenceSource.BOT)) {
            assertThatThrownBy(() -> absences.report(nepyivoda.getId(), backdated, teacherSource, "nepyivoda"))
                    .isInstanceOfSatisfying(BusinessRuleException.class, error -> {
                        assertThat(error.getCode()).isEqualTo("ABSENCE_IN_PAST");
                        assertThat(error.getMessage()).contains("лише з сьогодні");
                    });
        }
        assertThat(absences.report(nepyivoda.getId(), new AbsenceRequests.AbsenceData(today, today.plusDays(2), null,
                AbsenceReasonCategory.SICK_LEAVE, null), AbsenceSource.WEB, "nepyivoda").dateFrom()).isEqualTo(today);
        assertThat(absences.report(krentsin.getId(), backdated, AbsenceSource.ADMIN, "admin").dateFrom())
                .isEqualTo(today.minusDays(20));
    }

    @Test
    void absenceEditNeedsTheVersionItWasMadeFrom() {
        AbsenceRequests.AbsenceData data = new AbsenceRequests.AbsenceData(MONDAY, MONDAY, List.of(3),
                AbsenceReasonCategory.OTHER, "R3");
        AbsenceDto created = absences.report(nepyivoda.getId(), data, AbsenceSource.ADMIN, "admin");
        AbsenceRequests.AbsenceData edited = new AbsenceRequests.AbsenceData(MONDAY, MONDAY, List.of(3),
                AbsenceReasonCategory.PERSONAL, null);

        assertThatThrownBy(() -> absences.update(created.id(), nepyivoda.getId(), edited, null, "admin"))
                .isInstanceOfSatisfying(FieldValidationException.class, error -> assertThat(error.getErrors())
                        .extracting(FieldErrorDto::field).containsExactly("version"));
        assertThatThrownBy(() -> absences.update(created.id(), nepyivoda.getId(), edited, created.version() + 1,
                "admin")).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(absences.get(created.id()).comment()).isEqualTo("R3");
        assertThat(absences.update(created.id(), nepyivoda.getId(), edited, created.version(), "admin")
                .reasonCategory()).isEqualTo(AbsenceReasonCategory.PERSONAL);
    }

    @Test
    void lessonOfAnotherDayIsAMismatch() {
        assertThatThrownBy(() -> commands.create(new CreateChangeRequest(ChangeKind.REPLACE_TEACHER, MONDAY.plusDays(1),
                2, null, group.getId(), null, lesson.getId(), null, nepyivoda.getId(), null, null, ReasonCode.ABSENT,
                null, null), "admin"))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        error -> assertThat(error.getCode()).isEqualTo("CHANGE_LESSON_MISMATCH"));
    }

    @Test
    void staleVersionIsRejected() {
        ChangeDto created = commands.create(replaceTeacher(nepyivoda), "admin");

        assertThatThrownBy(() -> commands.update(created.id(),
                new UpdateChangeRequest(null, ReasonCode.MUTUAL, null, created.version() + 7), "admin"))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        ChangeDto edited = commands.update(created.id(),
                new UpdateChangeRequest(null, ReasonCode.MUTUAL, null, created.version()), "admin");
        assertThat(edited.printedReason()).isEqualTo("Зв.зам.");
        assertThat(edited.version()).isGreaterThan(created.version());
    }

    /**
     * The teacher's confirm and decline, the dispatcher's cancel and edit of one change at the same
     * moment: they queue up on the date lock, one wins and every loser fails with a conflict or a
     * stale version (both 409), never a deadlock or an untranslated error (500).
     */
    @Test
    void concurrentDecisionsOnOneChangeQueueUpAndLosersGetAConflict() throws Exception {
        LocalDate day = LocalDate.now(clock).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        Lesson future = fixture.lesson(semester, day, 3, group, 0, stp, krentsin, room);
        for (int round = 0; round < 3; round++) {
            ChangeDto created = commands.create(new CreateChangeRequest(ChangeKind.REPLACE_TEACHER, day, 3, null,
                    group.getId(), null, future.getId(), null, nepyivoda.getId(), null, null, ReasonCode.ABSENT, null,
                    null), "admin");

            List<Object> results = Parallel.run(
                    () -> replies.replyAsUser(created.id(), user(nepyivoda), ConfirmationDecision.CONFIRM, null),
                    () -> replies.replyAsUser(created.id(), user(nepyivoda), ConfirmationDecision.DECLINE, null),
                    () -> {
                        commands.cancel(created.id(), "admin");
                        return "cancelled";
                    },
                    () -> commands.update(created.id(), new UpdateChangeRequest(null, ReasonCode.MUTUAL, null,
                            created.version()), "admin"));

            assertThat(results).as("round %d", round).allSatisfy(result -> {
                if (result instanceof RuntimeException failure) {
                    assertThat(failure).isInstanceOfAny(ConflictException.class,
                            OptimisticLockingFailureException.class, jakarta.persistence.OptimisticLockException.class,
                            StaleStateException.class);
                }
            });
            assertThat(results).as("round %d: one decision wins", round)
                    .filteredOn(result -> !(result instanceof RuntimeException)).isNotEmpty();
            Change after = changes.findById(created.id()).orElseThrow();
            if (after.isOpen()) {
                commands.cancel(created.id(), "admin");
            }
        }
    }

    @Test
    void overdueConfirmationIsRemindedExactlyOnce() {
        EventProbe probe = EventProbe.listen(amqpAdmin, rabbitTemplate,
                RozkladEvents.SUBSTITUTION_CONFIRMATION_REQUESTED);
        ChangeDto created = commands.create(replaceTeacher(nepyivoda), "admin");
        Instant passed = clock.instant().minus(Duration.ofMinutes(5));
        transaction.executeWithoutResult(status ->
                changes.findById(created.id()).orElseThrow().startConfirmationRound(passed));

        reminders.remindOverdue();

        assertThat(changes.findById(created.id()).orElseThrow().getRemindedAt()).isNotNull();
        List<SubstitutionConfirmationRequested> sent = probe.await(SubstitutionConfirmationRequested.class,
                event -> event.changeId().equals(created.id()), 2, WAIT);
        assertThat(sent).hasSize(2);
        assertThat(sent.getLast().deadline()).isBefore(sent.getLast().occurredAt());

        assertThat(reminders.remindOverdue()).isZero();
        assertThat(probe.await(SubstitutionConfirmationRequested.class,
                event -> event.changeId().equals(created.id()), 1, Duration.ofSeconds(2))).isEmpty();
    }

    private CreateChangeRequest additional(int subgroup) {
        return new CreateChangeRequest(ChangeKind.ADDITIONAL, MONDAY, 5, null, group.getId(), subgroup, null,
                stp.getId(), nepyivoda.getId(), null, null, ReasonCode.OTHER, "Відпрацювання", null);
    }

    private CreateChangeRequest replaceTeacher(Teacher substitute) {
        return new CreateChangeRequest(ChangeKind.REPLACE_TEACHER, MONDAY, 2, null, group.getId(), null,
                lesson.getId(), null, substitute.getId(), null, null, ReasonCode.ABSENT, null, null);
    }

    /** The demo account linked to the teacher (Keycloak ids of the realm import). */
    private static CurrentUser user(Teacher teacher) {
        String username = TestJwts.KRENTSIN_ID.equals(teacher.getKeycloakUserId()) ? "krentsin" : "nepyivoda";
        return new CurrentUser(teacher.getKeycloakUserId(), username, Set.of("TEACHER"));
    }
}
