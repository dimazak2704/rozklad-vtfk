package ua.vtc.rozklad.core.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.contracts.SubstitutionRequestMode;
import ua.vtc.rozklad.contracts.events.RozkladEvents;
import ua.vtc.rozklad.contracts.events.SubstitutionConfirmationRequested;
import ua.vtc.rozklad.contracts.events.SubstitutionStatusChanged;
import ua.vtc.rozklad.contracts.events.SubstitutionTeacherReplied;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.order.internal.draft.OrderDrafts;
import ua.vtc.rozklad.core.order.internal.publish.OrderPublication;
import ua.vtc.rozklad.core.order.internal.view.OrderDto;
import ua.vtc.rozklad.core.shared.domain.ReasonCode;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.security.CurrentUser;
import ua.vtc.rozklad.core.substitution.fixture.EventProbe;
import ua.vtc.rozklad.core.substitution.fixture.Parallel;
import ua.vtc.rozklad.core.substitution.fixture.ScheduleFixture;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeCommands;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeDto;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeReplies;
import ua.vtc.rozklad.core.substitution.internal.change.ConfirmationReminders;
import ua.vtc.rozklad.core.substitution.internal.change.CreateChangeRequest;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonRepository;
import ua.vtc.rozklad.core.timetable.LessonStatus;

/**
 * Approval mode NOTIFY (the default, DOMAIN §7) on real PostgreSQL and RabbitMQ: the dispatcher assigns,
 * the change is confirmed at once and the teacher is informed; «Добре» is recorded, «Не можу» before the
 * order declines the change, «Не можу» after the order is an objection that never undoes the order; double
 * taps are idempotent and an answer racing with the publication takes whichever state the lock shows.
 */
@CoreIntegrationTest
class NotifyApprovalIT {

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
    private LessonRepository lessons;

    @Autowired
    private SubstitutionOverview overview;

    @Autowired
    private OrderDrafts drafts;

    @Autowired
    private OrderPublication publication;

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
    private StudentGroup group;
    private Teacher krentsin;
    private Teacher nepyivoda;
    private Discipline stp;
    private Discipline ndks;
    private Room room;

    @BeforeEach
    void schedule() {
        fixture = new ScheduleFixture(transaction, entityManager, jdbc);
        semester = fixture.semester();
        group = fixture.group("4КІ1", 4);
        krentsin = fixture.teacher("Кренцін М.Д.", "Кренцін", TestJwts.KRENTSIN_ID, null);
        nepyivoda = fixture.teacher("Непийвода М.В.", "Непийвода", TestJwts.NEPYIVODA_ID, null);
        stp = fixture.discipline("СТП", "Сучасні технології проєктування");
        ndks = fixture.discipline("НДКС", "Надійність, діагностика та експлуатація комп'ютерних систем і мереж");
        room = fixture.room("6");
        // без рядка в setting діє SystemSettings.DEFAULTS — NOTIFY
    }

    @AfterEach
    void cleanUp() {
        fixture.cleanUp();
    }

    @Test
    void assignedChangeIsConfirmedAtOnceAndTheTeacherIsInformed() {
        EventProbe probe = EventProbe.listen(amqpAdmin, rabbitTemplate,
                RozkladEvents.SUBSTITUTION_CONFIRMATION_REQUESTED, RozkladEvents.SUBSTITUTION_STATUS_CHANGED);
        Lesson lesson = stpLesson(MONDAY, 2);

        ChangeDto created = commands.create(otherDiscipline(lesson, ReasonCode.MUTUAL), "admin");

        assertThat(created.status()).isEqualTo(ChangeStatus.CONFIRMED);
        assertThat(created.approvalMode()).isEqualTo(ApprovalMode.NOTIFY);
        assertThat(created.confirmDeadline()).isNull();
        assertThat(created.teacherAnswer()).isNull();
        assertThat(created.history()).singleElement().satisfies(entry -> {
            assertThat(entry.from()).isNull();
            assertThat(entry.to()).isEqualTo(ChangeStatus.CONFIRMED);
            assertThat(entry.comment()).isEqualTo("Створено і підтверджено, викладачу надіслано повідомлення");
        });
        // повідомлення, а не прохання: без строку; дисципліна — та, яку викладач проведе, і та, замість якої
        assertThat(probe.await(SubstitutionConfirmationRequested.class,
                event -> event.changeId().equals(created.id()), 1, WAIT)).singleElement().satisfies(event -> {
                    assertThat(event.mode()).isEqualTo(SubstitutionRequestMode.NOTIFY);
                    assertThat(event.deadline()).isNull();
                    assertThat(event.teacherId()).isEqualTo(nepyivoda.getId());
                    assertThat(event.kind()).isEqualTo(ChangeKind.REPLACE_DISCIPLINE);
                    assertThat(event.disciplineName())
                            .isEqualTo("Надійність, діагностика та експлуатація комп'ютерних систем і мереж");
                    assertThat(event.originalDisciplineName()).isEqualTo("Сучасні технології проєктування");
                    assertThat(event.originalTeacherName()).isEqualTo("Кренцін М.Д.");
                    assertThat(event.reasonCode()).isEqualTo("MUTUAL");
                });
        assertThat(probe.await(SubstitutionStatusChanged.class, event -> event.changeId().equals(created.id())
                && event.status() == ChangeStatus.CONFIRMED, 1, WAIT)).hasSize(1);
        // без строку немає ні очікування, ні нагадувань
        assertThat(overview.summarize(MONDAY).pending()).isZero();
        assertThat(reminders.remindOverdue()).isZero();
    }

    @Test
    void okIsRecordedOnceAndKeepsTheStatus() {
        EventProbe probe = EventProbe.listen(amqpAdmin, rabbitTemplate, RozkladEvents.SUBSTITUTION_TEACHER_REPLIED);
        ChangeDto created = commands.create(otherDiscipline(stpLesson(MONDAY, 2), ReasonCode.ABSENT), "admin");

        ChangeDto ok = replies.replyFromBot(created.id(), nepyivoda.getId(), ConfirmationDecision.CONFIRM, null);
        ChangeDto again = replies.replyAsUser(created.id(), user(nepyivoda), ConfirmationDecision.CONFIRM, null);

        assertThat(ok.status()).isEqualTo(ChangeStatus.CONFIRMED);
        assertThat(ok.teacherAnswer()).isEqualTo(ConfirmationDecision.CONFIRM);
        assertThat(ok.respondedAt()).isNotNull();
        assertThat(ok.history()).hasSize(2).last().satisfies(entry -> {
            assertThat(entry.from()).isEqualTo(ChangeStatus.CONFIRMED);
            assertThat(entry.to()).isEqualTo(ChangeStatus.CONFIRMED);
            assertThat(entry.comment()).isEqualTo("Викладач побачив заміну");
            assertThat(entry.actor()).isEqualTo("Непийвода М.В. (Telegram)");
        });
        assertThat(again.history()).as("a double tap changes nothing").hasSize(2);
        assertThat(again.version()).isEqualTo(ok.version());
        assertThat(probe.await(SubstitutionTeacherReplied.class, event -> event.changeId().equals(created.id()), 1,
                WAIT)).singleElement().satisfies(event -> {
                    assertThat(event.decision()).isEqualTo(ConfirmationDecision.CONFIRM);
                    assertThat(event.status()).isEqualTo(ChangeStatus.CONFIRMED);
                    assertThat(event.raisesObjection()).isFalse();
                    assertThat(event.pair()).isEqualTo(2);
                    assertThat(event.groupCode()).isEqualTo("4КІ1");
                });

        OrderDto order = publication.publish(drafts.draftFor(MONDAY).id(), "admin");
        assertThat(order.items()).hasSize(1);
        assertThat(changes.findById(created.id()).orElseThrow().getStatus()).isEqualTo(ChangeStatus.APPLIED);
    }

    @Test
    void cannotBeforeTheOrderDeclinesAndTheLessonNeedsADecisionAgain() {
        EventProbe probe = EventProbe.listen(amqpAdmin, rabbitTemplate, RozkladEvents.SUBSTITUTION_STATUS_CHANGED,
                RozkladEvents.SUBSTITUTION_TEACHER_REPLIED);
        Lesson lesson = stpLesson(MONDAY, 2);
        ChangeDto created = commands.create(otherDiscipline(lesson, ReasonCode.ABSENT), "admin");
        replies.replyAsUser(created.id(), user(nepyivoda), ConfirmationDecision.CONFIRM, null);

        ChangeDto declined = replies.replyAsUser(created.id(), user(nepyivoda), ConfirmationDecision.DECLINE,
                "Захворіла");

        assertThat(declined.status()).isEqualTo(ChangeStatus.DECLINED);
        assertThat(declined.teacherAnswer()).isEqualTo(ConfirmationDecision.DECLINE);
        assertThat(declined.responseComment()).isEqualTo("Захворіла");
        assertThat(declined.history()).extracting(ChangeDto.HistoryEntry::to)
                .containsExactly(ChangeStatus.CONFIRMED, ChangeStatus.CONFIRMED, ChangeStatus.DECLINED);
        assertThat(declined.history().getLast().from()).isEqualTo(ChangeStatus.CONFIRMED);
        assertThat(probe.await(SubstitutionStatusChanged.class, event -> event.changeId().equals(created.id())
                && event.status() == ChangeStatus.DECLINED, 1, WAIT)).singleElement()
                .satisfies(event -> assertThat(event.teacherId()).isEqualTo(nepyivoda.getId()));
        // пара знову без рішення, розклад не змінено, у чернетці розпорядження її немає
        assertThat(overview.summarize(MONDAY).declined()).isEqualTo(1);
        assertThat(lessons.findById(lesson.getId()).orElseThrow().getStatus()).isEqualTo(LessonStatus.SCHEDULED);
        assertThat(drafts.draftFor(MONDAY).items()).isEmpty();

        // повтор тієї самої відповіді — та сама зміна; «Добре» після відмови — 409
        assertThat(replies.replyFromBot(created.id(), nepyivoda.getId(), ConfirmationDecision.DECLINE, null).history())
                .hasSize(3);
        assertThatThrownBy(() -> replies.replyFromBot(created.id(), nepyivoda.getId(), ConfirmationDecision.CONFIRM,
                null)).isInstanceOf(ConflictException.class).hasMessageContaining("Ви вже відмовилися");
        // диспетчер не може підтвердити відхилену заміну за викладача, лише попросити ще раз або обрати інше
        assertThatThrownBy(() -> commands.confirm(created.id(), "admin"))
                .isInstanceOf(ua.vtc.rozklad.core.shared.error.BusinessRuleException.class);
        assertThat(commands.requestConfirmation(created.id(), "admin").status())
                .isEqualTo(ChangeStatus.PENDING_CONFIRMATION);
    }

    @Test
    void cannotAfterTheOrderIsAnObjectionThatKeepsThePublishedOrder() {
        EventProbe probe = EventProbe.listen(amqpAdmin, rabbitTemplate, RozkladEvents.SUBSTITUTION_TEACHER_REPLIED,
                RozkladEvents.SUBSTITUTION_STATUS_CHANGED);
        Lesson lesson = stpLesson(MONDAY, 2);
        ChangeDto created = commands.create(otherDiscipline(lesson, ReasonCode.ABSENT), "admin");
        publication.publish(drafts.draftFor(MONDAY).id(), "admin");
        List<Lesson> before = lessons.findAllByChangeIdIn(Set.of(created.id()));

        ChangeDto objected = replies.replyFromBot(created.id(), nepyivoda.getId(), ConfirmationDecision.DECLINE, null);

        assertThat(objected.status()).isEqualTo(ChangeStatus.APPLIED);
        assertThat(objected.teacherAnswer()).isEqualTo(ConfirmationDecision.DECLINE);
        assertThat(objected.history().getLast()).satisfies(entry -> {
            assertThat(entry.from()).isEqualTo(ChangeStatus.APPLIED);
            assertThat(entry.to()).isEqualTo(ChangeStatus.APPLIED);
            assertThat(entry.comment()).contains("потрібне доповнення");
        });
        assertThat(probe.await(SubstitutionTeacherReplied.class, event -> event.changeId().equals(created.id()), 1,
                WAIT)).singleElement().satisfies(event -> {
                    assertThat(event.raisesObjection()).isTrue();
                    assertThat(event.teacherId()).isEqualTo(nepyivoda.getId());
                });
        // опубліковане розпорядження не скасоване: пара замінника лишається, оригінал скасований
        assertThat(lessons.findAllByChangeIdIn(Set.of(created.id()))).extracting(Lesson::getId)
                .containsExactlyElementsOf(before.stream().map(Lesson::getId).toList());
        assertThat(lessons.findById(lesson.getId()).orElseThrow().getStatus()).isEqualTo(LessonStatus.CANCELLED);
        // подія відповіді й можливий перехід ідуть в одній транзакції: перехід уже був би в черзі
        assertThat(probe.await(SubstitutionStatusChanged.class, event -> event.changeId().equals(created.id())
                && event.status() == ChangeStatus.DECLINED, 1, Duration.ofSeconds(2))).isEmpty();

        // повторна відмова нічого не додає; «Добре» після заперечення — 409
        assertThat(replies.replyAsUser(created.id(), user(nepyivoda), ConfirmationDecision.DECLINE, null).history())
                .hasSize(objected.history().size());
        assertThatThrownBy(() -> replies.replyAsUser(created.id(), user(nepyivoda), ConfirmationDecision.CONFIRM,
                null)).isInstanceOf(ConflictException.class).hasMessageContaining("Диспетчер внесе доповнення");
    }

    @Test
    void cancelledChangeCannotBeAnswered() {
        ChangeDto created = commands.create(otherDiscipline(stpLesson(MONDAY, 2), ReasonCode.ABSENT), "admin");
        commands.cancel(created.id(), "admin");

        assertThatThrownBy(() -> replies.replyFromBot(created.id(), nepyivoda.getId(), ConfirmationDecision.DECLINE,
                null)).isInstanceOf(ConflictException.class).hasMessageContaining("Скасовано");
        assertThatThrownBy(() -> replies.replyFromBot(created.id(), krentsin.getId(), ConfirmationDecision.DECLINE,
                null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    /**
     * «Не можу» racing with the publication of the order: both take the date lock, so either the answer
     * comes first (the change is declined and the publication finds a stale draft, 409) or the order
     * comes first (the answer sees APPLIED and records an objection). The answer never fails.
     */
    @Test
    void cannotRacingWithThePublicationTakesWhicheverStateTheLockShows() throws Exception {
        for (int round = 0; round < 3; round++) {
            LocalDate day = MONDAY.plusDays(round);
            ChangeDto created = commands.create(otherDiscipline(stpLesson(day, 2), ReasonCode.ABSENT), "admin");
            OrderDto draft = drafts.draftFor(day);

            List<Object> results = Parallel.run(
                    () -> replies.replyFromBot(created.id(), nepyivoda.getId(), ConfirmationDecision.DECLINE, null),
                    () -> publication.publish(draft.id(), "admin"));

            assertThat(results.getFirst()).as("round %d: the answer never fails", round).isInstanceOf(ChangeDto.class);
            Change after = changes.findById(created.id()).orElseThrow();
            assertThat(after.getTeacherAnswer()).isEqualTo(ConfirmationDecision.DECLINE);
            if (after.getStatus() == ChangeStatus.APPLIED) {
                assertThat(results.get(1)).as("round %d: published first", round).isInstanceOf(OrderDto.class);
                assertThat(((ChangeDto) results.getFirst()).history().getLast().comment())
                        .contains("потрібне доповнення");
            } else {
                assertThat(after.getStatus()).as("round %d", round).isEqualTo(ChangeStatus.DECLINED);
                assertThat(results.get(1)).as("round %d: the draft went stale", round)
                        .isInstanceOf(ConflictException.class);
            }
        }
    }

    /**
     * Parallel answers of the teacher and a cancel by the dispatcher queue up; losers get a conflict (the
     * dispatcher's cancel reads the change before the lock, so it may lose on the version check instead).
     */
    @Test
    void concurrentAnswersAndCancelQueueUp() throws Exception {
        for (int round = 0; round < 3; round++) {
            ChangeDto created = commands.create(otherDiscipline(stpLesson(MONDAY.plusDays(round), 2),
                    ReasonCode.ABSENT), "admin");

            List<Object> results = Parallel.run(
                    () -> replies.replyAsUser(created.id(), user(nepyivoda), ConfirmationDecision.CONFIRM, null),
                    () -> replies.replyFromBot(created.id(), nepyivoda.getId(), ConfirmationDecision.DECLINE, null),
                    () -> {
                        commands.cancel(created.id(), "admin");
                        return "cancelled";
                    });

            assertThat(results).as("round %d", round).allSatisfy(result -> {
                if (result instanceof RuntimeException failure) {
                    assertThat(failure).isInstanceOfAny(ConflictException.class,
                            ua.vtc.rozklad.core.shared.error.BusinessRuleException.class,
                            org.springframework.dao.OptimisticLockingFailureException.class);
                }
            });
            assertThat(results.subList(0, 2)).as("round %d: the answers themselves never lose on the version",
                    round).noneMatch(result -> result instanceof org.springframework.dao.OptimisticLockingFailureException);
            Change after = changes.findById(created.id()).orElseThrow();
            assertThat(after.getStatus()).as("round %d", round)
                    .isIn(ChangeStatus.CONFIRMED, ChangeStatus.DECLINED, ChangeStatus.CANCELLED);
            if (after.getStatus() == ChangeStatus.DECLINED) {
                assertThat(after.getTeacherAnswer()).isEqualTo(ConfirmationDecision.DECLINE);
            }
        }
    }

    private Lesson stpLesson(LocalDate date, int pair) {
        return fixture.lesson(semester, date, pair, group, 0, stp, krentsin, room);
    }

    /** REPLACE_DISCIPLINE: Непийвода teaches her НДКС to the group instead of Кренцін's СТП. */
    private CreateChangeRequest otherDiscipline(Lesson lesson, ReasonCode reason) {
        return new CreateChangeRequest(ChangeKind.REPLACE_DISCIPLINE, lesson.getDate(), lesson.getPairNumber(), null,
                group.getId(), null, lesson.getId(), ndks.getId(), nepyivoda.getId(), null, null, reason, null, null);
    }

    private static CurrentUser user(Teacher teacher) {
        return new CurrentUser(teacher.getKeycloakUserId(), "nepyivoda", Set.of("TEACHER"));
    }
}
