package ua.vtc.rozklad.core.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
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
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.shared.security.CurrentUser;
import ua.vtc.rozklad.core.substitution.fixture.EventProbe;
import ua.vtc.rozklad.core.substitution.fixture.ScheduleFixture;
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
        assertThat(created.confirmDeadline()).isBetween(before.plus(Duration.ofMinutes(60)),
                clock.instant().plus(Duration.ofMinutes(60)));
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
        assertThatThrownBy(() -> commands.update(created.id(),
                new UpdateChangeRequest(null, ReasonCode.ABSENT, null, null), "admin"))
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
    void oneOpenDecisionPerLesson() {
        commands.create(replaceTeacher(nepyivoda), "admin");

        assertThatThrownBy(() -> commands.create(new CreateChangeRequest(ChangeKind.CANCEL, MONDAY, 2, null,
                group.getId(), null, lesson.getId(), null, null, null, null, ReasonCode.ABSENT, null, null), "admin"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("вже є рішення");
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
