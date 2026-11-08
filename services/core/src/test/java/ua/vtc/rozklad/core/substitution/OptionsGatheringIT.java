package ua.vtc.rozklad.core.substitution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ua.vtc.rozklad.contracts.AbsenceReasonCategory;
import ua.vtc.rozklad.contracts.AbsenceSource;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.core.calendar.Semester;
import ua.vtc.rozklad.core.catalog.Discipline;
import ua.vtc.rozklad.core.catalog.Room;
import ua.vtc.rozklad.core.catalog.StudentGroup;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.shared.domain.ReasonCode;
import ua.vtc.rozklad.core.substitution.fixture.ScheduleFixture;
import ua.vtc.rozklad.core.substitution.internal.absence.AbsenceDto;
import ua.vtc.rozklad.core.substitution.internal.absence.AbsenceRequests;
import ua.vtc.rozklad.core.substitution.internal.absence.AbsenceService;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeCommands;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeDto;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeReplies;
import ua.vtc.rozklad.core.substitution.internal.change.CreateChangeRequest;
import ua.vtc.rozklad.core.substitution.internal.options.DayPlanDto;
import ua.vtc.rozklad.core.substitution.internal.options.LessonOptions;
import ua.vtc.rozklad.core.substitution.internal.options.OptionDto;
import ua.vtc.rozklad.core.substitution.internal.options.TeacherAvailability;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.timetable.Lesson;

/**
 * Reading the inputs of the substitute ranking from the database (DOMAIN §7): the group's own pool,
 * lessons freed by open changes, stream siblings, decliners, the manual teacher list, the day plan
 * and the number of SQL statements per request.
 */
@CoreIntegrationTest
class OptionsGatheringIT {

    private static final Logger LOG = LoggerFactory.getLogger(OptionsGatheringIT.class);
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);
    /**
     * Budget of one options request (v2 spec §8). Baseline: v1 ({@code ed08158}) issued 49 statements
     * for the same scenario (measured with the same statistics, 08.11.2026); v2 issues 28.
     */
    private static final int LESSON_BUDGET = 30;

    @Autowired
    private LessonOptions lessonOptions;

    @Autowired
    private TeacherAvailability availability;

    @Autowired
    private ChangeCommands changeCommands;

    @Autowired
    private ChangeReplies replies;

    @Autowired
    private ChangeRepository changes;

    @Autowired
    private AbsenceService absences;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private ScheduleFixture fixture;
    private Semester semester;
    private StudentGroup ki11;
    private StudentGroup ki12;
    private StudentGroup ki21;
    private Teacher badun;
    private Teacher nepyivoda;
    private Teacher hanza;
    private Teacher ostapiuk;
    private Teacher vernyhora;
    private Teacher krysak;
    private Teacher mozharovska;
    private Discipline math;
    private Discipline physics;
    private Discipline ict;
    private Discipline informatics;
    private Discipline chemistry;
    private Discipline english;
    private Room room12;
    private Room room21;

    @BeforeEach
    void college() {
        fixture = new ScheduleFixture(transaction, entityManager, jdbc);
        semester = fixture.semester();
        ki11 = fixture.group("1КІ1", 1);
        ki12 = fixture.group("1КІ2", 1);
        ki21 = fixture.group("2КІ1", 2);
        badun = fixture.teacher("Бадунь О.І.", "Бадунь");
        nepyivoda = fixture.teacher("Непийвода М.В.", "Непийвода");
        hanza = fixture.teacher("Ганза І.П.", "Ганза");
        ostapiuk = fixture.teacher("Остапюк В.В.", "Остапюк");
        vernyhora = fixture.teacher("Вернигора Н.М.", "Вернигора");
        krysak = fixture.teacher("Крисак Л.В.", "Крисак Л.");
        mozharovska = fixture.teacher("Можаровська Т.П.", "Можаровська");
        math = fixture.discipline("Матем.", "Математика");
        physics = fixture.discipline("Фіз.", "Фізика");
        ict = fixture.discipline("ІКТ", "Інформаційно-комунікаційні технології");
        informatics = fixture.discipline("Інф.", "Інформатика");
        chemistry = fixture.discipline("Хімія", "Хімія");
        english = fixture.discipline("Ін.мова", "Іноземна мова");
        Discipline sport = fixture.discipline("ФВ", "Фізичне виховання");
        room12 = fixture.room("12");
        room21 = fixture.room("21");
        fixture.load(semester, ki11, math, 60, badun);
        fixture.load(semester, ki11, physics, 40, nepyivoda);
        fixture.load(semester, ki11, ict, 40, nepyivoda);
        fixture.load(semester, ki11, informatics, 40, hanza);
        fixture.load(semester, ki11, sport, 40, ostapiuk);
        fixture.load(semester, ki11, chemistry, 40, vernyhora);
        fixture.splitLoad(semester, ki11, english, 40, krysak, mozharovska);
    }

    @AfterEach
    void cleanUp() {
        fixture.cleanUp();
    }

    /** 1КІ1 pair 2 (Матем., Бадунь); Ганза's own pair 2 is cancelled by an open decision; Остапюк is busy. */
    private Lesson mondayWithAFreedTeacherABusyOneAndADecliner() {
        Lesson target = fixture.lesson(semester, MONDAY, 2, ki11, 0, math, badun, room12);
        fixture.lesson(semester, MONDAY, 1, ki11, 0, physics, nepyivoda, room12);
        Lesson hanzaElsewhere = fixture.lesson(semester, MONDAY, 2, ki21, 0, informatics, hanza, room21);
        fixture.lesson(semester, MONDAY, 2, ki12, 0, informatics, ostapiuk, fixture.room("31"));
        changeCommands.create(new CreateChangeRequest(ChangeKind.CANCEL, MONDAY, 2, null, ki21.getId(), null,
                hanzaElsewhere.getId(), null, null, null, null, ReasonCode.ABSENT, null, null), "admin");
        ChangeDto asked = changeCommands.create(new CreateChangeRequest(ChangeKind.REPLACE_DISCIPLINE, MONDAY, 2, null,
                ki11.getId(), null, target.getId(), chemistry.getId(), vernyhora.getId(), null, null,
                ReasonCode.MUTUAL, null, null), "admin");
        assertThat(asked.status()).isEqualTo(ChangeStatus.PENDING_CONFIRMATION);
        replies.replyFromBot(asked.id(), vernyhora.getId(), ConfirmationDecision.DECLINE, "Не можу");
        return target;
    }

    @Test
    void thePoolIsTheGroupsOwnTeachersWhoAreFreeAndHaveNotDeclined() {
        Lesson target = mondayWithAFreedTeacherABusyOneAndADecliner();

        OptionDto.LessonOptions result = lessonOptions.of(target.getId());

        assertThat(result.options()).extracting(OptionDto::kind).doesNotContain(ChangeKind.REPLACE_TEACHER);
        assertThat(result.options()).filteredOn(option -> option.kind() == ChangeKind.REPLACE_DISCIPLINE)
                .extracting(option -> option.teacher().shortName())
                .containsExactlyInAnyOrder("Непийвода М.В.", "Ганза І.П.");
        assertThat(result.options()).filteredOn(option -> option.kind() == ChangeKind.REPLACE_DISCIPLINE
                        && option.teacher().id().equals(nepyivoda.getId())).singleElement()
                .satisfies(option -> assertThat(option.reasons()).anyMatch(reason -> reason.startsWith("Також веде: ")));
        assertThat(result.declined()).singleElement()
                .satisfies(declined -> assertThat(declined.teacher().id()).isEqualTo(vernyhora.getId()));
        assertThat(result.past()).isTrue();
        assertThat(result.options()).noneMatch(OptionDto::recommended);
    }

    /** A teacher whose own lesson an open decision takes away is free for a new one (also when creating it). */
    @Test
    void aFreedTeacherCanBeAssigned() {
        Lesson target = mondayWithAFreedTeacherABusyOneAndADecliner();

        ChangeDto change = changeCommands.create(new CreateChangeRequest(ChangeKind.REPLACE_DISCIPLINE, MONDAY, 2,
                null, ki11.getId(), null, target.getId(), informatics.getId(), hanza.getId(), null, null,
                ReasonCode.MUTUAL, null, null), "admin");

        assertThat(change.status()).isEqualTo(ChangeStatus.PENDING_CONFIRMATION);
    }

    @Test
    void aSubgroupLessonTakesOnlyItsSubgroupsTeachersAndMayMergeWithTheOtherSubgroup() {
        fixture.splitLoad(semester, ki11, informatics, 40, hanza, vernyhora);
        Lesson target = fixture.lesson(semester, MONDAY, 3, ki11, 1, english, krysak, room12);
        Lesson partner = fixture.lesson(semester, MONDAY, 3, ki11, 2, english, mozharovska, room21);

        List<OptionDto> options = lessonOptions.of(target.getId()).options();

        assertThat(options).filteredOn(option -> option.kind() == ChangeKind.REPLACE_DISCIPLINE)
                .extracting(option -> option.teacher().id()).containsExactly(hanza.getId());
        assertThat(options).filteredOn(option -> option.kind() == ChangeKind.MERGE_SUBGROUPS).singleElement()
                .satisfies(merge -> {
                    assertThat(merge.teacher().id()).isEqualTo(mozharovska.getId());
                    assertThat(merge.swapLesson().id()).isEqualTo(partner.getId());
                    assertThat(merge.suggestedRoom().name()).isEqualTo("21");
                });
    }

    /** H5: an assignment in the target's own stream does not make the teacher busy; one class, one substitute. */
    @Test
    void aStreamSiblingDoesNotMakeTheTeacherBusy() {
        fixture.load(semester, ki12, physics, 40, nepyivoda);
        fixture.load(semester, ki12, math, 60, badun);
        UUID stream = UUID.randomUUID();
        Lesson first = fixture.streamLesson(semester, MONDAY, 4, ki11, math, badun, room12, stream);
        Lesson second = fixture.streamLesson(semester, MONDAY, 4, ki12, math, badun, room12, stream);
        changeCommands.create(new CreateChangeRequest(ChangeKind.REPLACE_DISCIPLINE, MONDAY, 4, null, ki12.getId(),
                null, second.getId(), physics.getId(), nepyivoda.getId(), null, null, ReasonCode.MUTUAL, null, null),
                "admin");

        assertThat(lessonOptions.of(first.getId()).options())
                .anyMatch(option -> option.kind() == ChangeKind.REPLACE_DISCIPLINE
                        && option.teacher().id().equals(nepyivoda.getId()));
        ChangeDto together = changeCommands.create(new CreateChangeRequest(ChangeKind.REPLACE_DISCIPLINE, MONDAY, 4,
                null, ki11.getId(), null, first.getId(), physics.getId(), nepyivoda.getId(), null, null,
                ReasonCode.MUTUAL, null, null), "admin");
        assertThat(together.status()).isEqualTo(ChangeStatus.PENDING_CONFIRMATION);
    }

    @Test
    void manualTeacherListShowsEveryonesStateFreeFirst() {
        Lesson target = mondayWithAFreedTeacherABusyOneAndADecliner();
        absences.report(mozharovska.getId(), new AbsenceRequests.AbsenceData(MONDAY, MONDAY, null,
                AbsenceReasonCategory.SICK_LEAVE, null), AbsenceSource.ADMIN, "admin");
        fixture.preference(nepyivoda, semester, MONDAY, 2, PreferenceKind.UNAVAILABLE);

        List<TeacherAvailability.Item> items = availability.of(target.getId(), null);

        assertThat(items).extracting(item -> item.teacher().shortName(), TeacherAvailability.Item::status)
                .startsWith(org.assertj.core.groups.Tuple.tuple("Ганза І.П.", TeacherAvailability.Status.FREE))
                .contains(
                        org.assertj.core.groups.Tuple.tuple("Остапюк В.В.", TeacherAvailability.Status.BUSY),
                        org.assertj.core.groups.Tuple.tuple("Вернигора Н.М.", TeacherAvailability.Status.DECLINED),
                        org.assertj.core.groups.Tuple.tuple("Можаровська Т.П.", TeacherAvailability.Status.ABSENT),
                        org.assertj.core.groups.Tuple.tuple("Непийвода М.В.", TeacherAvailability.Status.UNAVAILABLE));
        assertThat(items).noneMatch(item -> item.teacher().id().equals(badun.getId()));
        assertThat(items).filteredOn(item -> item.status() == TeacherAvailability.Status.BUSY)
                .extracting(TeacherAvailability.Item::detail).containsExactly("2 пара, 1КІ2");
        assertThat(items).filteredOn(item -> item.status() == TeacherAvailability.Status.DECLINED).singleElement()
                .satisfies(item -> assertThat(item.detail()).matches("\\d{2}:\\d{2}"));
        assertThat(availability.of(target.getId(), "непий")).extracting(item -> item.teacher().id())
                .containsExactly(nepyivoda.getId());
    }

    /** The day plan proposes and creates nothing; a past lesson gets no proposal. */
    @Test
    void dayPlanProposesWithoutCreatingAnything() {
        LocalDate day = nextWeekday(LocalDate.now(clock).plusDays(7));
        assumeThat(day).as("the fixture semester must still be ahead").isBefore(ScheduleFixture.SEMESTER_END);
        fixture.lesson(semester, day, 2, ki11, 0, math, badun, room12);
        fixture.lesson(semester, day, 4, ki11, 0, math, badun, room12);
        AbsenceDto absence = absences.report(badun.getId(), new AbsenceRequests.AbsenceData(day, day, null,
                AbsenceReasonCategory.SICK_LEAVE, null), AbsenceSource.ADMIN, "admin");
        long before = changes.count();

        DayPlanDto plan = lessonOptions.plan(absence.id(), day);

        assertThat(plan.lessons()).extracting(item -> item.lesson().pair()).containsExactly(2, 4);
        assertThat(plan.lessons()).allSatisfy(item -> {
            assertThat(item.past()).isFalse();
            assertThat(item.proposedIndex()).isNotNull();
            assertThat(item.options().get(item.proposedIndex()).kind()).isEqualTo(ChangeKind.REPLACE_DISCIPLINE);
        });
        assertThat(changes.count()).isEqualTo(before);
    }

    @Test
    void aPastDayPlanHasNoProposals() {
        fixture.lesson(semester, MONDAY, 2, ki11, 0, math, badun, room12);
        AbsenceDto absence = absences.report(badun.getId(), new AbsenceRequests.AbsenceData(MONDAY, MONDAY, null,
                AbsenceReasonCategory.SICK_LEAVE, null), AbsenceSource.ADMIN, "admin");

        assertThat(lessonOptions.plan(absence.id(), null).lessons()).singleElement().satisfies(item -> {
            assertThat(item.past()).isTrue();
            assertThat(item.proposedIndex()).isNull();
        });
    }

    /**
     * Statement budget (v2 spec §8): one options request ≤ 30 statements, a day plan ≤ 15 + 8 per
     * affected group. Hibernate counts the JPA statements; the lesson views are read with one
     * JdbcClient query that it does not see.
     */
    @Test
    void readsTheDayWithABoundedNumberOfStatements() {
        Lesson target = mondayWithAFreedTeacherABusyOneAndADecliner();
        fixture.lesson(semester, MONDAY, 3, ki12, 0, math, badun, room12);
        fixture.load(semester, ki12, physics, 40, nepyivoda);
        AbsenceDto absence = absences.report(badun.getId(), new AbsenceRequests.AbsenceData(MONDAY, MONDAY, null,
                AbsenceReasonCategory.SICK_LEAVE, null), AbsenceSource.ADMIN, "admin");

        long perLesson = statements(() -> lessonOptions.of(target.getId()));
        long dayPlan = statements(() -> lessonOptions.plan(absence.id(), MONDAY));
        long availabilityList = statements(() -> availability.of(target.getId(), null));

        LOG.info("statements: options={}, day plan (2 groups)={}, teacher availability={}", perLesson, dayPlan,
                availabilityList);
        assertThat(perLesson).isLessThanOrEqualTo(LESSON_BUDGET);
        assertThat(dayPlan).isLessThanOrEqualTo(15 + 8 * 2);
        assertThat(availabilityList).isLessThanOrEqualTo(15);
    }

    private long statements(Supplier<?> call) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            call.get();
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(enabled);
        }
    }

    private static LocalDate nextWeekday(LocalDate date) {
        LocalDate day = date;
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            day = day.plusDays(1);
        }
        return day;
    }
}
