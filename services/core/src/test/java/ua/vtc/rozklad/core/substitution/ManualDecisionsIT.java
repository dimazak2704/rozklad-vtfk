package ua.vtc.rozklad.core.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.core.accounting.TeacherActivity;
import ua.vtc.rozklad.core.accounting.TeacherActivityProvider;
import ua.vtc.rozklad.core.accounting.internal.AccountingService;
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
import ua.vtc.rozklad.core.shared.error.FieldErrorDto;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;
import ua.vtc.rozklad.core.substitution.fixture.ScheduleFixture;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeCommands;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeDto;
import ua.vtc.rozklad.core.substitution.internal.change.CreateChangeRequest;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonRepository;
import ua.vtc.rozklad.core.timetable.LessonStatus;

/**
 * Decisions the dispatcher makes by hand (DOMAIN §7): several changes in one transaction, merging
 * two subgroups through order publication, accounting and the teacher statistics.
 */
@CoreIntegrationTest
class ManualDecisionsIT {

    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 21);

    @Autowired
    private ChangeCommands changeCommands;

    @Autowired
    private ChangeRepository changes;

    @Autowired
    private LessonRepository lessons;

    @Autowired
    private OrderDrafts drafts;

    @Autowired
    private OrderPublication publication;

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

    private ScheduleFixture fixture;
    private Semester semester;
    private StudentGroup ki12;
    private Teacher krysak;
    private Teacher mozharovska;
    private Teacher nepyivoda;
    private Teacher hanza;
    private Discipline english;
    private Discipline physics;
    private Discipline informatics;
    private Room room110;
    private Room room12;
    private Lesson krysakLesson;
    private Lesson mozharovskaLesson;
    private Lesson physicsLesson;

    @BeforeEach
    void college() {
        fixture = new ScheduleFixture(transaction, entityManager, jdbc);
        semester = fixture.semester();
        ki12 = fixture.group("1КІ2", 1);
        krysak = fixture.teacher("Крисак Л.В.", "Крисак Л.");
        mozharovska = fixture.teacher("Можаровська Т.П.", "Можаровська");
        nepyivoda = fixture.teacher("Непийвода М.В.", "Непийвода");
        hanza = fixture.teacher("Ганза І.П.", "Ганза");
        english = fixture.discipline("Ін.мова", "Іноземна мова");
        physics = fixture.discipline("Фіз.", "Фізика");
        informatics = fixture.discipline("Інф.", "Інформатика");
        room110 = fixture.room("110");
        room12 = fixture.room("12");
        fixture.splitLoad(semester, ki12, english, 40, krysak, mozharovska);
        fixture.load(semester, ki12, physics, 40, nepyivoda);
        fixture.load(semester, ki12, informatics, 40, hanza);
        krysakLesson = fixture.lesson(semester, WEDNESDAY, 2, ki12, 1, english, krysak, room110);
        mozharovskaLesson = fixture.lesson(semester, WEDNESDAY, 2, ki12, 2, english, mozharovska, room12);
        physicsLesson = fixture.lesson(semester, WEDNESDAY, 3, ki12, 0, physics, nepyivoda, room12);
        fixture.setting("substitution.approvalMode", "AUTO");
    }

    @AfterEach
    void cleanUp() {
        fixture.cleanUp();
    }

    private CreateChangeRequest cancel(Lesson lesson) {
        return new CreateChangeRequest(ChangeKind.CANCEL, lesson.getDate(), lesson.getPairNumber(), null,
                lesson.getGroupId(), null, lesson.getId(), null, null, null, null, ReasonCode.ABSENT, null, null);
    }

    private CreateChangeRequest additional(LocalDate date, int pair, Teacher teacher, Discipline discipline) {
        return new CreateChangeRequest(ChangeKind.ADDITIONAL, date, pair, null, ki12.getId(), 0, null,
                discipline.getId(), teacher.getId(), room12.getId(), null, ReasonCode.OTHER, null, null);
    }

    @Test
    void aBatchCreatesEveryChangeInOrder() {
        List<ChangeDto> created = changeCommands.createAll(List.of(cancel(physicsLesson),
                additional(WEDNESDAY, 4, nepyivoda, physics)), "admin");

        assertThat(created).extracting(ChangeDto::kind).containsExactly(ChangeKind.CANCEL, ChangeKind.ADDITIONAL);
        assertThat(changes.findAllByDateOrderByCreatedAt(WEDNESDAY)).hasSize(2);
    }

    /** One 409 in the batch: nothing is created (the make-up pair of a cancel is atomic). */
    @Test
    void aConflictInTheSecondChangeRollsBackTheFirst() {
        // 3 пару групи вже займає фізика: додаткова пара на 3 — конфлікт групи
        assertThatThrownBy(() -> changeCommands.createAll(List.of(cancel(krysakLesson),
                additional(WEDNESDAY, 3, hanza, informatics)), "admin"))
                .isInstanceOf(ConflictException.class);

        assertThat(changes.findAllByDateOrderByCreatedAt(WEDNESDAY)).isEmpty();
    }

    @Test
    void aFieldErrorNamesTheItemOfTheBatch() {
        CreateChangeRequest unknownTeacher = new CreateChangeRequest(ChangeKind.ADDITIONAL, WEDNESDAY, 5, null,
                ki12.getId(), 0, null, physics.getId(), UUID.fromString("00000000-0000-4000-8000-000000000000"),
                null, null, ReasonCode.OTHER, null, null);

        assertThatThrownBy(() -> changeCommands.createAll(List.of(cancel(krysakLesson), unknownTeacher), "admin"))
                .isInstanceOfSatisfying(FieldValidationException.class, error -> assertThat(error.getErrors())
                        .extracting(FieldErrorDto::field).containsExactly("changes[1].newTeacherId"));
        assertThat(changes.findAllByDateOrderByCreatedAt(WEDNESDAY)).isEmpty();
    }

    /**
     * «Об'єднати підгрупи»: Можаровська takes both subgroups of Ін.мова in her room. The order prints
     * one MERGE_SUBGROUPS row; publication leaves one whole-group lesson; Крисак misses a pair,
     * Можаровська teaches one pair (no substitution); both subgroups keep the hours.
     */
    @Test
    void mergingSubgroupsGoesThroughTheOrderAndTheStatistics() {
        ChangeDto merge = changeCommands.create(new CreateChangeRequest(ChangeKind.MERGE_SUBGROUPS, WEDNESDAY, 2,
                null, ki12.getId(), null, krysakLesson.getId(), null, null, null, mozharovskaLesson.getId(),
                ReasonCode.ABSENT, null, null), "admin");

        assertThat(merge.status()).isEqualTo(ChangeStatus.CONFIRMED);
        assertThat(merge.replacement().teacher().id()).isEqualTo(mozharovska.getId());
        assertThat(merge.replacement().discipline().id()).isEqualTo(english.getId());
        assertThat(merge.replacement().room().name()).isEqualTo("12");

        OrderDto draft = drafts.draftFor(WEDNESDAY);
        assertThat(draft.items()).singleElement().satisfies(item -> {
            assertThat(item.rowType()).isEqualTo(OrderRowType.MERGE_SUBGROUPS);
            assertThat(item.groupCode()).isEqualTo("1КІ2");
            assertThat(item.pairLabel()).isEqualTo("2");
            assertThat(item.fromDiscipline()).isEqualTo("Ін.мова");
            assertThat(item.fromTeacher()).isEqualTo("Крисак Л. (п.)");
            assertThat(item.reasonText()).isEqualTo("Відп.");
            assertThat(item.toDiscipline()).isEqualTo("Ін.мова");
            assertThat(item.toTeacher()).isEqualTo("Можаровська");
            assertThat(item.roomText()).isEqualTo("12");
        });

        publication.publish(draft.id(), "admin");

        assertThat(changes.findById(merge.id()).orElseThrow().getStatus()).isEqualTo(ChangeStatus.APPLIED);
        List<Lesson> pairTwo = lessons.findAllByGroupIdAndDateBetweenOrderByDateAscPairNumberAsc(ki12.getId(),
                WEDNESDAY, WEDNESDAY).stream().filter(lesson -> lesson.getPairNumber() == 2).toList();
        assertThat(pairTwo).filteredOn(lesson -> lesson.getStatus() == LessonStatus.SCHEDULED).singleElement()
                .satisfies(merged -> {
                    assertThat(merged.getSubgroupNumber()).isZero();
                    assertThat(merged.getTeacherId()).isEqualTo(mozharovska.getId());
                    assertThat(merged.getRoomId()).isEqualTo(room12.getId());
                    assertThat(merged.getChangeId()).isEqualTo(merge.id());
                });
        assertThat(pairTwo).filteredOn(lesson -> lesson.getStatus() == LessonStatus.CANCELLED)
                .extracting(Lesson::getId)
                .containsExactlyInAnyOrder(krysakLesson.getId(), mozharovskaLesson.getId());

        List<TeacherActivity> october = teacherActivity.activityBetween(LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31));
        assertThat(october).filteredOn(row -> row.teacherId().equals(krysak.getId())).singleElement()
                .satisfies(row -> assertThat(row.missedPairs()).isEqualTo(1));
        assertThat(october).noneMatch(row -> row.teacherId().equals(mozharovska.getId())
                && (row.substitutions() > 0 || row.missedPairs() > 0));
        // обидві підгрупи мали цю пару: ведомість бачить одне заняття для всієї групи
        assertThat(accounting.progressOfGroup(semester.getId(), ki12.getId()).get(english.getId()).forecast())
                .isEqualTo(2);
    }

    @Test
    void mergeNeedsTheSameDisciplineOfAnotherSubgroupAtThePair() {
        assertThatThrownBy(() -> changeCommands.create(new CreateChangeRequest(ChangeKind.MERGE_SUBGROUPS, WEDNESDAY,
                2, null, ki12.getId(), null, krysakLesson.getId(), null, null, null, physicsLesson.getId(),
                ReasonCode.ABSENT, null, null), "admin"))
                .hasMessageContaining("тією самою дисципліною іншої підгрупи");
    }

    /** A future day may swap an earlier pair into the slot (the group comes later). */
    @Test
    void aFutureDayMaySwapAnEarlierPair() {
        Lesson first = fixture.lesson(semester, WEDNESDAY.plusYears(1), 1, ki12, 0, informatics, hanza, room12);
        Lesson absent = fixture.lesson(semester, WEDNESDAY.plusYears(1), 3, ki12, 0, physics, nepyivoda, room12);

        ChangeDto swap = changeCommands.create(new CreateChangeRequest(ChangeKind.SWAP, absent.getDate(), 3, null,
                ki12.getId(), null, absent.getId(), null, null, null, first.getId(), ReasonCode.ABSENT, null, null),
                "admin");

        assertThat(swap.swapFromPair()).isEqualTo(1);
        assertThatThrownBy(() -> changeCommands.create(new CreateChangeRequest(ChangeKind.SWAP, WEDNESDAY, 3, null,
                ki12.getId(), null, physicsLesson.getId(), null, null, null, krysakLesson.getId(), ReasonCode.ABSENT,
                null, null), "admin")).hasMessageContaining("наступних пар");
    }
}
