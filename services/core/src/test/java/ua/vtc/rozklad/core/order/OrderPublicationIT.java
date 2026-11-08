package ua.vtc.rozklad.core.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderStatus;
import ua.vtc.rozklad.contracts.events.OrderPayload;
import ua.vtc.rozklad.contracts.events.OrderPublished;
import ua.vtc.rozklad.contracts.events.RozkladEvents;
import ua.vtc.rozklad.contracts.events.ScheduleChangeReason;
import ua.vtc.rozklad.contracts.events.ScheduleChanged;
import ua.vtc.rozklad.core.calendar.CalendarPeriodKind;
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
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.substitution.ChangeRepository;
import ua.vtc.rozklad.core.substitution.fixture.EventProbe;
import ua.vtc.rozklad.core.substitution.fixture.ScheduleFixture;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeCommands;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeDto;
import ua.vtc.rozklad.core.substitution.internal.change.CreateChangeRequest;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonOrigin;
import ua.vtc.rozklad.core.timetable.LessonRepository;

/**
 * The real order №35 of 19.10.2026 (DOMAIN §11) rebuilt from changes: the draft with the exact
 * college texts, publication with the 35th number of the semester, the lessons it applies, the
 * events it sends, an amendment, a stale draft and a conflict found at publication.
 */
@CoreIntegrationTest
class OrderPublicationIT {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);
    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    private ChangeCommands changeCommands;

    @Autowired
    private OrderDrafts drafts;

    @Autowired
    private OrderPublication publication;

    @Autowired
    private ChangeRepository changes;

    @Autowired
    private LessonRepository lessons;

    @Autowired
    private OrderDocumentRepository orders;

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
    private RestTestClient client;

    private ScheduleFixture fixture;
    private Semester semester;
    private StudentGroup tk1;
    private StudentGroup ki12;
    private StudentGroup m1;
    private StudentGroup op12;
    private StudentGroup f3;
    private StudentGroup ki41;
    private StudentGroup ki42;
    private Teacher krysak;
    private Teacher prokopchuk;
    private Teacher nepyivoda;
    private Lesson tk1EnglishFirstSubgroup;
    private Lesson ki12English;
    private Lesson m1English;
    private Lesson f3English;
    private Lesson ki41Stp;
    private Lesson ki42Stp;
    private Lesson ki41Ndks;
    private Discipline zu;
    private Discipline bzh;
    private Discipline ndks;
    private Discipline opcz;
    private Teacher yemelianenko;
    private Teacher pohosian;
    private Teacher hanza;
    private Room room204;
    private Room room403;
    private Room room12;

    @BeforeEach
    void collegeDay() {
        fixture = new ScheduleFixture(transaction, entityManager, jdbc);
        semester = fixture.semester();
        tk1 = fixture.group("1ТК1", 1);
        ki12 = fixture.group("1КІ2", 1);
        m1 = fixture.group("1М", 1);
        op12 = fixture.group("1ОП2", 1);
        StudentGroup ki23 = fixture.group("2КІ3", 2);
        f3 = fixture.group("3Ф", 3);
        ki41 = fixture.group("4КІ1", 4);
        ki42 = fixture.group("4КІ2", 4);

        yemelianenko = fixture.teacher("Ємельяненко О.П.", "Ємельяненко");
        pohosian = fixture.teacher("Погосян А.Г.", "Погосян");
        krysak = fixture.teacher("Крисак Л.В.", "Крисак Л.");
        Teacher mozharovska = fixture.teacher("Можаровська І.В.", "Можаровська");
        Teacher sharovarska = fixture.teacher("Шароварська Т.М.", "Шароварська");
        prokopchuk = fixture.teacher("Прокопчук О.В.", "Прокопчук");
        Teacher krentsin = fixture.teacher("Кренцін М.Д.", "Кренцін");
        nepyivoda = fixture.teacher("Непийвода М.В.", "Непийвода");
        hanza = fixture.teacher("Ганза І.П.", "Ганза");

        zu = fixture.discipline("ЗУ", "Захист України");
        bzh = fixture.discipline("БЖ", "Безпека життєдіяльності");
        Discipline english = fixture.discipline("Ін. мова", "Іноземна мова");
        Discipline culture = fixture.discipline("Культ.", "Культурологія");
        Discipline stp = fixture.discipline("СТП", "Сучасні технології проєктування");
        ndks = fixture.discipline("НДКС", "Надійність, діагностика та експлуатація комп'ютерних систем і мереж");
        opcz = fixture.discipline("ОПЦЗ", "Охорона праці та цивільний захист");

        room204 = fixture.room("204");
        room403 = fixture.room("403");
        Room room110 = fixture.room("110");
        room12 = fixture.room("12");
        Room room405 = fixture.room("405");
        Room room6 = fixture.room("6");

        tk1EnglishFirstSubgroup = fixture.lesson(semester, MONDAY, 1, tk1, 1, english, krysak, room110);
        fixture.lesson(semester, MONDAY, 1, tk1, 2, english, mozharovska, room12);
        for (int pair = 2; pair <= 4; pair++) {
            fixture.lesson(semester, MONDAY, pair, tk1, 0, culture, sharovarska, room405);
        }
        ki12English = fixture.lesson(semester, MONDAY, 2, ki12, 0, english, krysak, room110);
        m1English = fixture.lesson(semester, MONDAY, 3, m1, 0, english, krysak, room110);
        f3English = fixture.lesson(semester, MONDAY, 4, f3, 1, english, krysak, room110);
        ki41Stp = fixture.lesson(semester, MONDAY, 2, ki41, 0, stp, krentsin, room6);
        ki41Ndks = fixture.lesson(semester, MONDAY, 3, ki41, 0, ndks, nepyivoda, room6);
        ki42Stp = fixture.lesson(semester, MONDAY, 4, ki42, 0, stp, krentsin, room6);

        fixture.period(semester, CalendarPeriodKind.PRACTICE, "Електрорадіомонтажна практика",
                LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 30),
                "2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р.", List.of(ki23.getId()));
        fixture.period(semester, CalendarPeriodKind.STUDY_MODE, "Дистанційне навчання 4КІ",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31),
                "Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно.", List.of(ki41.getId(), ki42.getId()));
        fixture.setting("substitution.approvalMode", "AUTO");
        // 34 навчальні дні з 01.09.2026 — №1..№34, тож 19.10 отримає №35
        fixture.publishedOrders(semester, studyDays(ScheduleFixture.SEMESTER_START, MONDAY.minusDays(1)));
    }

    @AfterEach
    void cleanUp() {
        fixture.cleanUp();
    }

    @Test
    void draftAndPublicationReproduceOrder35() {
        EventProbe probe = EventProbe.listen(amqpAdmin, rabbitTemplate, RozkladEvents.ORDER_PUBLISHED,
                RozkladEvents.SCHEDULE_CHANGED);
        List<UUID> changeIds = createOrder35Changes();

        OrderDto draft = drafts.draftFor(MONDAY);

        assertThat(draft.kind()).isEqualTo(OrderKind.ORDER);
        assertThat(draft.status()).isEqualTo(OrderStatus.DRAFT);
        assertThat(draft.number()).isNull();
        assertThat(draft.title()).isEqualTo("РОЗПОРЯДЖЕННЯ");
        assertThat(draft.subtitle()).isEqualTo("По коледжу про деякі зміни в розкладі занять");
        assertThat(draft.dateLabel()).isEqualTo("На 19 жовтня 2026 р.");
        assertThat(draft.weekdayLabel() + " - " + draft.parityLabel()).isEqualTo("ПОНЕДІЛОК - ЗНАМЕННИК");
        assertThat(draft.signatoryPosition()).isEqualTo("Заступник директора з НР");
        assertThat(draft.signatoryName()).isEqualTo("Микола ПОЛІЩУК");
        assertThat(draft.notes()).containsExactly(
                "Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно.",
                "2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р.");
        assertThat(draft.items()).extracting(OrderPublicationIT::printed).containsExactly(
                "1КІ2 | 2 | REPLACEMENT | Ін. мова | Крисак Л. | Відп. | Ін. мова | Прокопчук | 110",
                "1М | 3 | REPLACEMENT | Ін. мова | Крисак Л. | Відп. | Ін. мова | Прокопчук | 110",
                "1ОП2 | 4 | ADDITIONAL | ДОДАТКОВО |  |  | БЖ | Погосян | 403",
                "1ТК1 | 1-4 | BLOCK | ЗУ | Ємельяненко |  |  |  | 204",
                "1ТК1 | 1 | CANCELLATION | Ін. мова | Крисак Л. (п.) | Відп. | Пари не буде |  | ",
                "3Ф | 4 | CANCELLATION | Ін. мова | Крисак Л. (п.) | Відп. | Пари не буде |  | ",
                "4КІ1 | 2 | REPLACEMENT | СТП | Кренцін | Зв.зам. | НДКС | Непийвода | 6",
                "4КІ2 | 4 | REPLACEMENT | СТП | Кренцін | Зв.зам. | ОПЦЗ | Ганза | 6");
        assertThat(draft.roomChanges()).isEmpty();
        assertThat(drafts.draftFor(MONDAY).id()).as("refresh keeps the same draft").isEqualTo(draft.id());

        OrderDto published = publication.publish(draft.id(), "admin");

        assertThat(published.number()).isEqualTo(35);
        assertThat(published.title()).isEqualTo("РОЗПОРЯДЖЕННЯ №35");
        assertThat(published.status()).isEqualTo(OrderStatus.PUBLISHED);
        assertThat(published.publishedBy()).isEqualTo("admin");
        assertLessonsApplied(published.id(), changeIds);
        assertThat(probe.await(OrderPublished.class, event -> event.order().id().equals(published.id()), 1, WAIT))
                .singleElement().satisfies(event -> {
                    OrderPayload payload = event.order();
                    assertThat(payload.number()).isEqualTo(35);
                    assertThat(payload.semesterName()).isEqualTo("I семестр 2026/2027");
                    assertThat(payload.items()).hasSize(8);
                    assertThat(payload.notes()).hasSize(2);
                    assertThat(payload.affectedGroups()).extracting(OrderPayload.AffectedGroup::code)
                            .containsExactly("1КІ2", "1М", "1ОП2", "1ТК1", "3Ф", "4КІ1", "4КІ2");
                    assertThat(payload.affectedTeachers()).extracting(OrderPayload.AffectedTeacher::name)
                            .contains("Прокопчук О.В.", "Крисак Л.В.", "Ємельяненко О.П.", "Ганза І.П.");
                });
        assertThat(probe.await(ScheduleChanged.class, event -> event.reason() == ScheduleChangeReason.ORDER_PUBLISHED
                && event.dates().equals(List.of(MONDAY)), 1, WAIT)).singleElement()
                .satisfies(event -> assertThat(event.groupIds()).hasSize(7));

        client.get().uri("/api/public/changes?date=2026-10-19").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.parity").isEqualTo("DENOMINATOR")
                .jsonPath("$.weekdayLabel").isEqualTo("Понеділок")
                .jsonPath("$.order.number").isEqualTo(35)
                .jsonPath("$.groups.length()").isEqualTo(7)
                .jsonPath("$.groups[3].group.code").isEqualTo("1ТК1")
                .jsonPath("$.groups[3].items[1].subgroupNote").isEqualTo("1 підгрупа")
                .jsonPath("$.groups[3].items[1].to.discipline").isEqualTo("Пари не буде");
    }

    @Test
    void changeConfirmedAfterPublicationGoesIntoAnAmendment() {
        createOrder35Changes();
        OrderDto main = publication.publish(drafts.draftFor(MONDAY).id(), "admin");
        changeCommands.create(new CreateChangeRequest(ChangeKind.ROOM_CHANGE, MONDAY, 3, null, ki41.getId(), null,
                ki41Ndks.getId(), null, null, room12.getId(), null, ReasonCode.OTHER, null, null), "admin");

        OrderDto amendment = drafts.draftFor(MONDAY);

        assertThat(amendment.kind()).isEqualTo(OrderKind.AMENDMENT);
        assertThat(amendment.parentId()).isEqualTo(main.id());
        assertThat(amendment.title()).isEqualTo("ДОПОВНЕННЯ до розпорядження №35");
        assertThat(amendment.items()).isEmpty();
        assertThat(amendment.notes()).isEmpty();
        assertThat(amendment.roomChanges()).extracting(OrderPublicationIT::printed)
                .containsExactly("4КІ1 | 3 | ROOM_CHANGE |  |  |  |  |  | 12");

        OrderDto published = publication.publish(amendment.id(), "admin");

        assertThat(published.number()).isEqualTo(36);
        assertThat(published.title()).isEqualTo("ДОПОВНЕННЯ до розпорядження №35");
        assertThat(drafts.draftFor(MONDAY).id()).as("nothing new: the latest published document")
                .isEqualTo(published.id());
        assertThat(lessons.findAllByGroupIdAndDateBetweenOrderByDateAscPairNumberAsc(ki41.getId(), MONDAY, MONDAY))
                .filteredOn(lesson -> lesson.isScheduled() && lesson.getPairNumber() == 3)
                .singleElement().satisfies(lesson -> {
                    assertThat(lesson.getRoomId()).isEqualTo(room12.getId());
                    assertThat(lesson.getTeacherId()).isEqualTo(nepyivoda.getId());
                });
    }

    @Test
    void staleDraftIsNotPublished() {
        ChangeDto first = replaceTeacher(ki12English, prokopchuk);
        OrderDto draft = drafts.draftFor(MONDAY);
        ChangeDto second = replaceTeacher(m1English, prokopchuk);

        assertThatThrownBy(() -> publication.publish(draft.id(), "admin"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Чернетка застаріла");

        OrderDocument order = orders.findById(draft.id()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order.getNumber()).isNull();
        assertThat(changes.findAllById(List.of(first.id(), second.id())))
                .allSatisfy(change -> assertThat(change.getStatus()).isEqualTo(ChangeStatus.CONFIRMED));
        assertThat(publication.publish(drafts.draftFor(MONDAY).id(), "admin").number()).isEqualTo(35);
    }

    @Test
    void conflictFoundAtPublicationPublishesNothing() {
        ChangeDto change = replaceTeacher(ki12English, prokopchuk);
        // розклад змінили вже після підтвердження: Прокопчук тепер веде 2 пару в 1М
        Lesson clash = fixture.lesson(semester, MONDAY, 2, m1, 0, bzh, prokopchuk, room403);
        OrderDto draft = drafts.draftFor(MONDAY);

        assertThatThrownBy(() -> publication.publish(draft.id(), "admin"))
                .isInstanceOfSatisfying(ConflictException.class, conflict -> assertThat(conflict.getConflicts())
                        .anySatisfy(found -> {
                            assertThat(found.kind()).isEqualTo(ConflictKind.TEACHER);
                            assertThat(found.lessonIds()).containsExactly(clash.getId());
                        }));

        assertThat(orders.findById(draft.id()).orElseThrow().getNumber()).isNull();
        assertThat(changes.findById(change.id()).orElseThrow().getStatus()).isEqualTo(ChangeStatus.CONFIRMED);
        assertThat(lessons.findById(ki12English.getId()).orElseThrow().isScheduled()).isTrue();
        assertThat(lessons.findAllByGroupIdAndDateBetweenOrderByDateAscPairNumberAsc(ki12.getId(), MONDAY, MONDAY))
                .noneMatch(lesson -> lesson.getOrigin() == LessonOrigin.CHANGE);
    }

    private List<UUID> createOrder35Changes() {
        List<UUID> ids = new ArrayList<>();
        ids.add(changeCommands.create(new CreateChangeRequest(ChangeKind.BLOCK, MONDAY, 1, 4, tk1.getId(), 0, null,
                zu.getId(), yemelianenko.getId(), room204.getId(), null, ReasonCode.OTHER, "Навчальні збори", null),
                "admin").id());
        ids.add(cancel(tk1EnglishFirstSubgroup).id());
        ids.add(replaceTeacher(ki12English, prokopchuk).id());
        ids.add(replaceTeacher(m1English, prokopchuk).id());
        ids.add(changeCommands.create(new CreateChangeRequest(ChangeKind.ADDITIONAL, MONDAY, 4, null, op12.getId(), 0,
                null, bzh.getId(), pohosian.getId(), room403.getId(), null, ReasonCode.OTHER, null, null), "admin").id());
        ids.add(cancel(f3English).id());
        ids.add(replaceDiscipline(ki41Stp, ndks, nepyivoda).id());
        ids.add(replaceDiscipline(ki42Stp, opcz, hanza).id());
        return ids;
    }

    private void assertLessonsApplied(UUID orderId, List<UUID> changeIds) {
        assertThat(changes.findAllById(changeIds)).allSatisfy(change -> {
            assertThat(change.getStatus()).isEqualTo(ChangeStatus.APPLIED);
            assertThat(change.getOrderId()).isEqualTo(orderId);
        });
        List<Lesson> ki12Day = lessons.findAllByGroupIdAndDateBetweenOrderByDateAscPairNumberAsc(ki12.getId(), MONDAY,
                MONDAY);
        assertThat(lessons.findById(ki12English.getId()).orElseThrow().isScheduled()).isFalse();
        assertThat(ki12Day).filteredOn(Lesson::isScheduled).singleElement().satisfies(lesson -> {
            assertThat(lesson.getTeacherId()).isEqualTo(prokopchuk.getId());
            assertThat(lesson.getOrigin()).isEqualTo(LessonOrigin.CHANGE);
            assertThat(lesson.getChangeId()).isIn(changeIds);
        });
        List<Lesson> tk1Day = lessons.findAllByGroupIdAndDateBetweenOrderByDateAscPairNumberAsc(tk1.getId(), MONDAY,
                MONDAY);
        assertThat(tk1Day).filteredOn(Lesson::isScheduled).hasSize(4).allSatisfy(lesson -> {
            assertThat(lesson.getOrigin()).isEqualTo(LessonOrigin.BLOCK);
            assertThat(lesson.getTeacherId()).isEqualTo(yemelianenko.getId());
            assertThat(lesson.getRoomId()).isEqualTo(room204.getId());
        });
        assertThat(lessons.findAllByGroupIdAndDateBetweenOrderByDateAscPairNumberAsc(op12.getId(), MONDAY, MONDAY))
                .singleElement().satisfies(lesson -> assertThat(lesson.getOrigin()).isEqualTo(LessonOrigin.ADDITIONAL));
        assertThat(lessons.findById(f3English.getId()).orElseThrow().isScheduled()).isFalse();
        assertThat(lessons.findAllByGroupIdAndDateBetweenOrderByDateAscPairNumberAsc(ki41.getId(), MONDAY, MONDAY))
                .filteredOn(lesson -> lesson.isScheduled() && lesson.getPairNumber() == 2)
                .singleElement().satisfies(lesson -> assertThat(lesson.getDisciplineId()).isEqualTo(ndks.getId()));
    }

    private ChangeDto replaceTeacher(Lesson lesson, Teacher substitute) {
        return changeCommands.create(new CreateChangeRequest(ChangeKind.REPLACE_TEACHER, lesson.getDate(),
                lesson.getPairNumber(), null, lesson.getGroupId(), null, lesson.getId(), null, substitute.getId(),
                null, null, ReasonCode.ABSENT, null, null), "admin");
    }

    private ChangeDto replaceDiscipline(Lesson lesson, Discipline discipline, Teacher teacher) {
        return changeCommands.create(new CreateChangeRequest(ChangeKind.REPLACE_DISCIPLINE, lesson.getDate(),
                lesson.getPairNumber(), null, lesson.getGroupId(), null, lesson.getId(), discipline.getId(),
                teacher.getId(), null, null, ReasonCode.MUTUAL, null, null), "admin");
    }

    private ChangeDto cancel(Lesson lesson) {
        return changeCommands.create(new CreateChangeRequest(ChangeKind.CANCEL, lesson.getDate(),
                lesson.getPairNumber(), null, lesson.getGroupId(), null, lesson.getId(), null, null, null, null,
                ReasonCode.ABSENT, null, null), "admin");
    }

    private static List<LocalDate> studyDays(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (date.getDayOfWeek().getValue() <= 5) {
                days.add(date);
            }
        }
        return days;
    }

    /** One printed row: Група | Пара | type | За розкладом | Причина | По заміні | Ауд. */
    private static String printed(OrderDto.Item item) {
        return String.join(" | ", item.groupCode(), item.pairLabel(), item.rowType().name(),
                text(item.fromDiscipline()), text(item.fromTeacher()), text(item.reasonText()),
                text(item.toDiscipline()), text(item.toTeacher()), text(item.roomText()));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
