package ua.vtc.rozklad.core.timetable.internal.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static ua.vtc.rozklad.core.calendar.CalendarFixtures.calendar;
import static ua.vtc.rozklad.core.calendar.CalendarFixtures.holiday;
import static ua.vtc.rozklad.core.calendar.CalendarFixtures.onlineForCourses;
import static ua.vtc.rozklad.core.calendar.CalendarFixtures.practice;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.D_FV;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.D_KM;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.D_STP;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.G_2KI1;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.G_4KI1;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.G_4KI2;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.R_304;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.R_GYM;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.T_KRENTSIN;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.facts;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.calendar.CalendarFixtures;
import ua.vtc.rozklad.core.calendar.StudyCalendar;
import ua.vtc.rozklad.core.calendar.StudyPeriod;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.timetable.TemplateLesson;

/** Materialization of DOMAIN.md §6 on «I семестр 2026/2027» (week of 05.10 is a denominator week). */
class MaterializationPlannerTest {

    private static final UUID VERSION_ID = UUID.randomUUID();
    private static final LocalDate MONDAY_DEN = LocalDate.of(2026, 10, 5);
    private static final LocalDate MONDAY_NUM = LocalDate.of(2026, 10, 12);
    private static final LocalDate FRIDAY_NUM = LocalDate.of(2026, 10, 16);

    private final MaterializationPlanner planner = new MaterializationPlanner();

    @Test
    void halvesAreMaterializedOnlyInTheirWeeks() {
        TemplateLesson every = lesson(1, 2, Parity.BOTH, G_4KI1);
        TemplateLesson numerator = lesson(1, 1, Parity.NUMERATOR, G_4KI1);
        TemplateLesson denominator = lesson(1, 3, Parity.DENOMINATOR, G_4KI1);

        MaterializationPlanner.Plan plan = plan(List.of(every, numerator, denominator), calendar(List.of()),
                List.of(), MONDAY_DEN, FRIDAY_NUM);

        assertThat(plan.lessons()).extracting(NewLesson::date, NewLesson::pair).containsExactlyInAnyOrder(
                tuple(MONDAY_DEN, 2), tuple(MONDAY_DEN, 3),
                tuple(MONDAY_NUM, 2), tuple(MONDAY_NUM, 1));
        assertThat(plan.dates()).hasSize(10).startsWith(MONDAY_DEN).endsWith(FRIDAY_NUM);
        assertThat(plan.lessons()).allSatisfy(lesson -> {
            assertThat(lesson.versionId()).isEqualTo(VERSION_ID);
            assertThat(lesson.semesterId()).isEqualTo(CalendarFixtures.SEMESTER_ID);
        });
    }

    @Test
    void parityOverrideChangesTheWeek() {
        TemplateLesson numerator = lesson(1, 1, Parity.NUMERATOR, G_4KI1);
        StudyCalendar overridden = calendar(List.of(), Map.of(MONDAY_NUM, Parity.DENOMINATOR));

        assertThat(plan(List.of(numerator), overridden, List.of(), MONDAY_DEN, FRIDAY_NUM).lessons()).isEmpty();
    }

    @Test
    void holidaysAndPracticeRemoveLessons() {
        TemplateLesson monday4ki1 = lesson(1, 1, Parity.BOTH, G_4KI1);
        TemplateLesson monday2ki1 = lesson(1, 5, Parity.BOTH, G_2KI1);
        List<StudyPeriod> periods = List.of(
                holiday("Осінні канікули", LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 30)),
                practice("Практика 2КІ1", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 23), G_2KI1));

        MaterializationPlanner.Plan plan = plan(List.of(monday4ki1, monday2ki1), calendar(periods), List.of(),
                LocalDate.of(2026, 10, 19), LocalDate.of(2026, 11, 6));

        assertThat(plan.lessons()).extracting(NewLesson::date, NewLesson::groupId).containsExactlyInAnyOrder(
                tuple(LocalDate.of(2026, 10, 19), G_4KI1),
                tuple(LocalDate.of(2026, 11, 2), G_4KI1),
                tuple(LocalDate.of(2026, 11, 2), G_2KI1));
    }

    @Test
    void studyModeMarksLessonsOnline() {
        TemplateLesson fourthCourse = lesson(1, 1, Parity.BOTH, G_4KI1);
        TemplateLesson secondCourse = lesson(1, 5, Parity.BOTH, G_2KI1);
        TemplateLesson onlineByTemplate = lesson(1, 6, Parity.BOTH, G_2KI1);
        onlineByTemplate.setOnline(true);

        MaterializationPlanner.Plan plan = plan(List.of(fourthCourse, secondCourse, onlineByTemplate),
                calendar(List.of(onlineForCourses("Дистанційно", CalendarFixtures.STARTS, LocalDate.of(2026, 10, 31),
                        4))), List.of(), MONDAY_DEN, MONDAY_DEN);

        assertThat(plan.lessons()).extracting(NewLesson::pair, NewLesson::online)
                .containsExactlyInAnyOrder(tuple(1, true),
                        tuple(5, false), tuple(6, true));
    }

    @Test
    void slotOfAnAppliedChangeIsLeftToTheChange() {
        TemplateLesson template = lesson(1, 2, Parity.BOTH, G_4KI1);
        KeptLesson replacement = kept(MONDAY_DEN, 2, G_4KI1, 0, null, null, true);
        KeptLesson cancelledOriginal = kept(MONDAY_NUM, 2, G_4KI1, 1, null, null, false);

        MaterializationPlanner.Plan plan = plan(List.of(template), calendar(List.of()),
                List.of(replacement, cancelledOriginal), MONDAY_DEN, FRIDAY_NUM);

        assertThat(plan.lessons()).isEmpty();
        assertThat(plan.coveredByChanges()).isEqualTo(2);
        assertThat(plan.clashes()).isEmpty();
    }

    /**
     * Order cancelled the denominator lesson of 05.10, then the week was overridden to a numerator
     * week: the cancelled lesson speaks only for its own (now not due) template lesson, so the
     * numerator lesson of the slot takes place.
     */
    @Test
    void cancelledLessonOfTheOtherWeekDoesNotHoldTheSlotAfterAParityOverride() {
        TemplateLesson numerator = lesson(1, 3, Parity.NUMERATOR, G_4KI1);
        TemplateLesson denominator = lesson(1, 3, Parity.DENOMINATOR, G_4KI1);
        denominator.setDisciplineId(D_KM);
        KeptLesson cancelled = rooted(MONDAY_DEN, 3, G_4KI1, denominator, VERSION_ID, false);

        MaterializationPlanner.Plan asItWas = plan(List.of(numerator, denominator), calendar(List.of()),
                List.of(cancelled), MONDAY_DEN, MONDAY_DEN);
        MaterializationPlanner.Plan overridden = plan(List.of(numerator, denominator),
                calendar(List.of(), Map.of(MONDAY_DEN, Parity.NUMERATOR)), List.of(cancelled), MONDAY_DEN,
                MONDAY_DEN);

        assertThat(asItWas.lessons()).isEmpty();
        assertThat(asItWas.coveredByChanges()).isOne();
        assertThat(overridden.lessons()).singleElement()
                .satisfies(lesson -> assertThat(lesson.templateLessonId()).isEqualTo(numerator.getId()));
        assertThat(overridden.clashes()).isEmpty();
    }

    /** A substitution of the denominator lesson cannot stay on a day that became a numerator day. */
    @Test
    void changedLessonOfTheOtherWeekIsReportedAndKeepsTheSlot() {
        TemplateLesson numerator = lesson(1, 3, Parity.NUMERATOR, G_4KI1);
        TemplateLesson denominator = lesson(1, 3, Parity.DENOMINATOR, G_4KI1);
        denominator.setDisciplineId(D_KM);
        KeptLesson substitution = rooted(MONDAY_DEN, 3, G_4KI1, denominator, VERSION_ID, true);

        MaterializationPlanner.Plan plan = plan(List.of(numerator, denominator),
                calendar(List.of(), Map.of(MONDAY_DEN, Parity.NUMERATOR)), List.of(substitution), MONDAY_DEN,
                MONDAY_DEN);

        assertThat(plan.lessons()).as("the group cannot have two lessons in the slot").isEmpty();
        assertThat(plan.found()).singleElement().satisfies(clash -> {
            assertThat(clash.subjectId()).isEqualTo(substitution.id());
            assertThat(clash.conflict().kind()).isEqualTo(ConflictKind.GROUP);
            assertThat(clash.conflict().lessonIds()).containsExactly(substitution.id());
            assertThat(clash.conflict().message()).startsWith("4КІ1 КМ (знаменник), 05.10.2026, 3 пара: заняття "
                    + "змінене розпорядженням");
        });
    }

    /** Across versions an applied change keeps governing its slot (republication, DOMAIN §6). */
    @Test
    void cancelledLessonOfAnotherVersionStillHoldsTheSlot() {
        TemplateLesson numerator = lesson(1, 3, Parity.NUMERATOR, G_4KI1);
        TemplateLesson olderDenominator = new TemplateLesson(UUID.randomUUID(), 1, 3, Parity.DENOMINATOR, G_4KI1, 0,
                D_KM);
        KeptLesson cancelled = rooted(MONDAY_DEN, 3, G_4KI1, olderDenominator, olderDenominator.getVersionId(), false);

        MaterializationPlanner.Plan plan = plan(List.of(numerator),
                calendar(List.of(), Map.of(MONDAY_DEN, Parity.NUMERATOR)), List.of(cancelled), MONDAY_DEN,
                MONDAY_DEN);

        assertThat(plan.lessons()).isEmpty();
        assertThat(plan.coveredByChanges()).isOne();
        assertThat(plan.clashes()).isEmpty();
    }

    @Test
    void teacherBusyInAKeptLessonIsReportedAndNotDoubleBooked() {
        TemplateLesson template = lesson(1, 2, Parity.BOTH, G_4KI2);
        template.setTeacherId(T_KRENTSIN);
        KeptLesson substitution = kept(MONDAY_DEN, 2, G_4KI1, 0, T_KRENTSIN, null, true);

        MaterializationPlanner.Plan plan = plan(List.of(template), calendar(List.of()), List.of(substitution),
                MONDAY_DEN, MONDAY_DEN);

        assertThat(plan.lessons()).isEmpty();
        assertThat(plan.clashes()).singleElement().satisfies(conflict -> {
            assertThat(conflict.kind()).isEqualTo(ConflictKind.TEACHER);
            assertThat(conflict.severity()).isEqualTo(ConflictDto.Severity.HARD);
            assertThat(conflict.date()).isEqualTo(MONDAY_DEN);
            assertThat(conflict.lessonIds()).containsExactly(substitution.id());
            assertThat(conflict.message()).isEqualTo("4КІ2 СТП не поставлено на 05.10.2026, 2 пара: викладач "
                    + "Кренцін М.Д. у цей час веде заняття, змінене розпорядженням");
        });
    }

    @Test
    void exclusiveRoomTakenByAKeptLessonIsReportedButASportsHallIsShared() {
        TemplateLesson inRoom = lesson(1, 2, Parity.BOTH, G_4KI2);
        inRoom.setRoomId(R_304);
        TemplateLesson inGym = lesson(1, 3, Parity.BOTH, G_4KI2);
        inGym.setRoomId(R_GYM);
        inGym.setDisciplineId(D_FV);

        MaterializationPlanner.Plan plan = plan(List.of(inRoom, inGym), calendar(List.of()), List.of(
                        kept(MONDAY_DEN, 2, G_4KI1, 0, null, R_304, true),
                        new KeptLesson(UUID.randomUUID(), MONDAY_DEN, 3, G_2KI1, 0, null, R_GYM, false, null, true)),
                MONDAY_DEN, MONDAY_DEN);

        assertThat(plan.clashes()).extracting(ConflictDto::kind).containsExactly(ConflictKind.ROOM);
        assertThat(plan.lessons()).singleElement().satisfies(lesson -> {
            assertThat(lesson.pair()).isEqualTo(3);
            assertThat(lesson.roomExclusive()).isFalse();
        });
    }

    @Test
    void onlineLessonKeepsItsRoomButDoesNotOccupyIt() {
        TemplateLesson remote = lesson(1, 2, Parity.BOTH, G_4KI2);
        remote.setRoomId(R_304);

        MaterializationPlanner.Plan plan = plan(List.of(remote),
                calendar(List.of(onlineForCourses("Дистанційно", CalendarFixtures.STARTS, LocalDate.of(2026, 10, 31),
                        4))), List.of(kept(MONDAY_DEN, 2, G_4KI1, 0, null, R_304, true)), MONDAY_DEN, MONDAY_DEN);

        assertThat(plan.clashes()).isEmpty();
        assertThat(plan.lessons()).singleElement().satisfies(lesson -> {
            assertThat(lesson.roomId()).isEqualTo(R_304);
            assertThat(lesson.online()).isTrue();
            assertThat(lesson.roomExclusive()).isFalse();
        });
    }

    @Test
    void newLessonCopiesTheTemplate() {
        TemplateLesson template = lesson(1, 1, Parity.BOTH, G_4KI1);
        template.setTeacherId(T_KRENTSIN);
        template.setRoomId(R_304);
        template.setDisciplineId(D_KM);

        NewLesson created = plan(List.of(template), calendar(List.of()), List.of(), MONDAY_DEN, MONDAY_DEN)
                .lessons().getFirst();

        assertThat(created.templateLessonId()).isEqualTo(template.getId());
        assertThat(created.disciplineId()).isEqualTo(D_KM);
        assertThat(created.teacherId()).isEqualTo(T_KRENTSIN);
        assertThat(created.roomId()).isEqualTo(R_304);
        assertThat(created.roomExclusive()).isTrue();
        assertThat(created.online()).isFalse();
    }

    private MaterializationPlanner.Plan plan(List<TemplateLesson> template, StudyCalendar calendar,
                                             List<KeptLesson> kept, LocalDate from, LocalDate to) {
        return planner.plan(new MaterializationPlanner.Input(CalendarFixtures.SEMESTER_ID, VERSION_ID, template,
                calendar, facts().build(), kept, from, to));
    }

    private static TemplateLesson lesson(int weekday, int pair, Parity parity, UUID groupId) {
        return new TemplateLesson(VERSION_ID, weekday, pair, parity, groupId, 0, D_STP);
    }

    /** A lesson an order created (scheduled) or cancelled, standing for {@code root}. */
    private static KeptLesson rooted(LocalDate date, int pair, UUID groupId, TemplateLesson root, UUID versionId,
                                     boolean scheduled) {
        return new KeptLesson(UUID.randomUUID(), date, pair, groupId, 0, null, null, true, null, scheduled, false, 4,
                false, new KeptLesson.Root(root.getId(), versionId, false), scheduled);
    }

    private static KeptLesson kept(LocalDate date, int pair, UUID groupId, int subgroup, UUID teacherId, UUID roomId,
                                   boolean scheduled) {
        return new KeptLesson(UUID.randomUUID(), date, pair, groupId, subgroup, teacherId, roomId, true, null,
                scheduled);
    }
}
