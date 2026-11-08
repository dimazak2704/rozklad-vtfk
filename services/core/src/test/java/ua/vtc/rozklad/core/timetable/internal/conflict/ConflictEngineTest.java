package ua.vtc.rozklad.core.timetable.internal.conflict;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.D_ENG;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.D_FV;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.D_KM;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.D_NDEKSM;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.D_STP;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.G_1TK1;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.G_2KI1;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.G_4KI1;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.G_4KI2;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.R_304;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.R_GYM;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.R_LAB6;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.T_KRENTSIN;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.T_NEPYIVODA;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.at;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.facts;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.on;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictKind;

/** Every rule of DOMAIN.md §5, for the weekly template and for dated lessons. */
class ConflictEngineTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);

    private final ConflictEngine engine = new ConflictEngine();

    private ConflictReport check(ConflictFacts facts, SlotLesson... lessons) {
        return engine.check(List.of(lessons), facts);
    }

    private ConflictReport check(SlotLesson... lessons) {
        return check(facts().build(), lessons);
    }

    private static List<ConflictDto> ofKind(List<ConflictDto> conflicts, ConflictKind kind) {
        return conflicts.stream().filter(conflict -> conflict.kind() == kind).toList();
    }

    @Nested
    class TeacherClash {

        @Test
        void teacherCannotTeachTwoGroupsAtOnce() {
            SlotLesson first = at(1, 2).teacher(T_KRENTSIN).build();
            SlotLesson second = at(1, 2).group(G_4KI2).discipline(D_NDEKSM).teacher(T_KRENTSIN).build();

            List<ConflictDto> conflicts = ofKind(check(first, second).hard(), ConflictKind.TEACHER);

            assertThat(conflicts).singleElement().satisfies(conflict -> {
                assertThat(conflict.lessonIds()).containsExactlyInAnyOrder(first.id(), second.id());
                assertThat(conflict.weekday()).isEqualTo(1);
                assertThat(conflict.date()).isNull();
                assertThat(conflict.pair()).isEqualTo(2);
                assertThat(conflict.message())
                        .isEqualTo("Викладач Кренцін М.Д. одночасно має кілька занять: 4КІ1 СТП, 4КІ2 НДЕКСМ "
                                + "(понеділок, 2 пара)");
            });
        }

        @Test
        void numeratorAndDenominatorLessonsDoNotMeet() {
            ConflictReport report = check(
                    at(1, 2).parity(Parity.NUMERATOR).teacher(T_KRENTSIN).build(),
                    at(1, 2).parity(Parity.DENOMINATOR).group(G_4KI2).teacher(T_KRENTSIN).build());

            assertThat(report.hard()).isEmpty();
        }

        @Test
        void everyWeekLessonMeetsANumeratorLessonOnlyInNumeratorWeeks() {
            ConflictReport report = check(
                    at(1, 2).teacher(T_KRENTSIN).build(),
                    at(1, 2).parity(Parity.NUMERATOR).group(G_4KI2).teacher(T_KRENTSIN).build());

            assertThat(ofKind(report.hard(), ConflictKind.TEACHER)).singleElement()
                    .satisfies(conflict -> assertThat(conflict.message()).endsWith("(понеділок, 2 пара, чисельник)"));
        }

        @Test
        void lessonsOfOneStreamShareTeacherAndRoom() {
            UUID stream = UUID.randomUUID();
            ConflictFacts lectureHall = facts().room(R_304, "304", RoomType.CLASSROOM, 60, 1).build();
            ConflictReport report = check(lectureHall,
                    at(1, 2).teacher(T_KRENTSIN).room(R_304).stream(stream).build(),
                    at(1, 2).group(G_4KI2).teacher(T_KRENTSIN).room(R_304).stream(stream).build());

            assertThat(report.hard()).isEmpty();
        }

        @Test
        void streamDoesNotExcuseALessonOutsideIt() {
            UUID stream = UUID.randomUUID();
            SlotLesson outside = at(1, 2).group(G_1TK1).teacher(T_KRENTSIN).build();
            ConflictReport report = check(
                    at(1, 2).teacher(T_KRENTSIN).stream(stream).build(),
                    at(1, 2).group(G_4KI2).teacher(T_KRENTSIN).stream(stream).build(),
                    outside);

            assertThat(ofKind(report.hard(), ConflictKind.TEACHER)).singleElement()
                    .satisfies(conflict -> assertThat(conflict.lessonIds()).hasSize(3).contains(outside.id()));
        }
    }

    @Nested
    class CohortClash {

        @Test
        void wholeGroupLessonOverlapsEverySubgroup() {
            SlotLesson whole = at(2, 3).build();
            SlotLesson subgroup = at(2, 3).subgroup(2).discipline(D_ENG).build();

            assertThat(ofKind(check(whole, subgroup).hard(), ConflictKind.GROUP)).singleElement()
                    .satisfies(conflict -> {
                        assertThat(conflict.lessonIds()).containsExactlyInAnyOrder(whole.id(), subgroup.id());
                        assertThat(conflict.message()).isEqualTo(
                                "Група 4КІ1 одночасно має кілька занять: СТП, Ін.мова (підгр. 2) (вівторок, 3 пара)");
                    });
        }

        @Test
        void subgroupsStudyInParallel() {
            ConflictReport report = check(
                    at(2, 3).subgroup(1).discipline(D_ENG).teacher(T_KRENTSIN).room(R_304).build(),
                    at(2, 3).subgroup(2).discipline(D_ENG).teacher(T_NEPYIVODA).room(R_LAB6).build());

            assertThat(report.hard()).isEmpty();
        }

        @Test
        void oneSubgroupCannotHaveTwoLessons() {
            ConflictReport report = check(at(2, 3).subgroup(1).build(), at(2, 3).subgroup(1).discipline(D_ENG).build());

            assertThat(ofKind(report.hard(), ConflictKind.GROUP)).hasSize(1);
        }

        @Test
        void twoHalvesInOneSlotAreAllowed() {
            ConflictReport report = check(at(3, 1).parity(Parity.NUMERATOR).build(),
                    at(3, 1).parity(Parity.DENOMINATOR).discipline(D_KM).build());

            assertThat(report.hard()).isEmpty();
        }
    }

    @Nested
    class Rooms {

        @Test
        void usualRoomHostsOneLesson() {
            ConflictReport report = check(at(1, 1).room(R_304).build(), at(1, 1).group(G_4KI2).room(R_304).build());

            assertThat(ofKind(report.hard(), ConflictKind.ROOM)).singleElement()
                    .satisfies(conflict -> assertThat(conflict.message())
                            .startsWith("Аудиторія 304 одночасно зайнята кількома заняттями: 4КІ1 СТП, 4КІ2 СТП"));
        }

        @Test
        void sportsHallHostsUpToMaxParallelGroups() {
            SlotLesson first = at(5, 2).discipline(D_FV).room(R_GYM).build();
            SlotLesson second = at(5, 2).group(G_4KI2).discipline(D_FV).room(R_GYM).build();
            SlotLesson third = at(5, 2).group(G_1TK1).discipline(D_FV).room(R_GYM).build();

            assertThat(check(first, second, third).hard()).isEmpty();

            SlotLesson fourth = at(5, 2).group(G_2KI1).discipline(D_FV).room(R_GYM).build();
            assertThat(ofKind(check(first, second, third, fourth).hard(), ConflictKind.ROOM)).singleElement()
                    .satisfies(conflict -> assertThat(conflict.message())
                            .startsWith("Аудиторія с.з. приймає до 3 груп одночасно, а заплановано 4"));
        }

        @Test
        void capacityIsCheckedForTheWholeGroup() {
            ConflictReport report = check(at(1, 1).room(R_LAB6).build());

            assertThat(ofKind(report.hard(), ConflictKind.CAPACITY)).singleElement()
                    .satisfies(conflict -> assertThat(conflict.message()).isEqualTo(
                            "Аудиторія 6 замала для 4КІ1 СТП: місткість — 15, студентів — 25 (понеділок, 1 пара)"));
        }

        @Test
        void subgroupOfKnownSizeFitsTheLab() {
            ConflictFacts facts = facts().subgroups(G_4KI1, 13, 12).build();

            assertThat(check(facts, at(1, 1).subgroup(1).room(R_LAB6).build()).hard()).isEmpty();
        }

        @Test
        void unknownSizeOrCapacitySkipsTheCheck() {
            ConflictFacts facts = facts().group(G_4KI1, "4КІ1", 4, null, 1).build();

            assertThat(check(facts, at(1, 1).room(R_LAB6).build()).hard()).isEmpty();
            assertThat(check(at(1, 1).subgroup(1).room(R_LAB6).build()).hard()).isEmpty();
            assertThat(check(at(1, 1).room(R_GYM).build()).hard()).isEmpty();
        }

        @Test
        void streamOccupancySumsItsGroups() {
            UUID stream = UUID.randomUUID();
            ConflictFacts facts = facts().room(R_304, "304", RoomType.CLASSROOM, 40, 1).build();

            ConflictReport report = check(facts, at(1, 1).room(R_304).stream(stream).build(),
                    at(1, 1).group(G_4KI2).room(R_304).stream(stream).build());

            assertThat(ofKind(report.hard(), ConflictKind.CAPACITY)).singleElement()
                    .satisfies(conflict -> assertThat(conflict.message()).contains("студентів — 50"));
            assertThat(ofKind(report.hard(), ConflictKind.ROOM)).isEmpty();
        }

        @Test
        void requiredRoomTypeMustMatch() {
            ConflictReport report = check(at(1, 1).room(R_304).requires(RoomType.COMPUTER_LAB).build());

            assertThat(ofKind(report.hard(), ConflictKind.ROOM_TYPE)).singleElement()
                    .satisfies(conflict -> assertThat(conflict.message()).isEqualTo(
                            "Для 4КІ1 СТП потрібен тип приміщення «компʼютерний клас», а 304 має тип «аудиторія» "
                                    + "(понеділок, 1 пара)"));
            assertThat(check(at(1, 1).subgroup(1).room(R_LAB6).requires(RoomType.COMPUTER_LAB).build()).hard())
                    .isEmpty();
        }
    }

    @Nested
    class ShiftAndAvailability {

        @Test
        void firstShiftGroupCannotHaveTheFifthPair() {
            ConflictReport report = check(at(1, 5).build());

            assertThat(ofKind(report.hard(), ConflictKind.SHIFT)).singleElement()
                    .satisfies(conflict -> assertThat(conflict.message()).isEqualTo(
                            "Група 4КІ1 навчається в 1 зміну (понеділок: пари 1–4), а СТП стоїть на 5 парі"));
        }

        @Test
        void secondShiftEndsEarlierOnFriday() {
            assertThat(check(at(1, 8).group(G_2KI1).build()).hard()).isEmpty();
            assertThat(ofKind(check(at(5, 7).group(G_2KI1).build()).hard(), ConflictKind.SHIFT)).hasSize(1);
            assertThat(ofKind(check(at(1, 3).group(G_2KI1).build()).hard(), ConflictKind.SHIFT)).hasSize(1);
        }

        @Test
        void unavailableTeacherIsAHardConflictAndUnwantedASoftOne() {
            ConflictFacts facts = facts()
                    .preference(T_KRENTSIN, 1, 1, SlotPreference.UNAVAILABLE)
                    .preference(T_KRENTSIN, 1, 2, SlotPreference.UNWANTED)
                    .build();

            ConflictReport report = check(facts, at(1, 1).teacher(T_KRENTSIN).build(),
                    at(1, 2).teacher(T_KRENTSIN).discipline(D_KM).build());

            assertThat(ofKind(report.hard(), ConflictKind.UNAVAILABLE)).singleElement()
                    .satisfies(conflict -> assertThat(conflict.message())
                            .isEqualTo("Викладач Кренцін М.Д. недоступний у цей час: 4КІ1 СТП (понеділок, 1 пара)"));
            assertThat(ofKind(report.soft(), ConflictKind.UNWANTED)).singleElement()
                    .satisfies(conflict -> assertThat(conflict.message())
                            .isEqualTo("Небажаний час для викладача Кренцін М.Д.: 4КІ1 КМ (понеділок, 2 пара)"));
        }
    }

    @Nested
    class SoftWarnings {

        @Test
        void freePairBetweenLessonsIsAWindowOfTheGroup() {
            SlotLesson first = at(1, 1).build();
            SlotLesson third = at(1, 3).discipline(D_KM).build();

            assertThat(ofKind(check(first, third).soft(), ConflictKind.WINDOW)).singleElement()
                    .satisfies(conflict -> {
                        assertThat(conflict.message()).isEqualTo("Вікно у групи 4КІ1 між 1 і 3 парою (понеділок)");
                        assertThat(conflict.pair()).isEqualTo(2);
                        assertThat(conflict.lessonIds()).containsExactlyInAnyOrder(first.id(), third.id());
                    });
        }

        @Test
        void windowOnlyInNumeratorWeeks() {
            ConflictReport report = check(at(1, 1).build(), at(1, 2).parity(Parity.DENOMINATOR).build(),
                    at(1, 3).discipline(D_KM).build());

            assertThat(ofKind(report.soft(), ConflictKind.WINDOW)).singleElement()
                    .satisfies(conflict -> assertThat(conflict.message())
                            .isEqualTo("Вікно у групи 4КІ1 між 1 і 3 парою (понеділок, чисельник)"));
        }

        @Test
        void windowOfOneSubgroupIsReportedForThatSubgroup() {
            ConflictFacts facts = facts().subgroups(G_4KI1, null, null).build();

            ConflictReport report = check(facts, at(1, 1).build(), at(1, 2).subgroup(1).discipline(D_ENG).build(),
                    at(1, 3).discipline(D_KM).build());

            assertThat(ofKind(report.soft(), ConflictKind.WINDOW)).singleElement()
                    .satisfies(conflict -> assertThat(conflict.message())
                            .isEqualTo("Вікно у групи 4КІ1 (підгр. 2) між 1 і 3 парою (понеділок)"));
        }

        @Test
        void freePairBetweenLessonsIsAWindowOfTheTeacher() {
            ConflictReport report = check(at(1, 1).teacher(T_KRENTSIN).build(),
                    at(1, 4).group(G_4KI2).teacher(T_KRENTSIN).build());

            assertThat(ofKind(report.soft(), ConflictKind.WINDOW)).singleElement()
                    .satisfies(conflict -> assertThat(conflict.message())
                            .isEqualTo("Вікно у викладача Кренцін М.Д. між 1 і 4 парою (понеділок)"));
        }

        @Test
        void extraNumeratorPairMakesABrokenDay() {
            SlotLesson half = at(3, 3).parity(Parity.NUMERATOR).discipline(D_KM).build();
            ConflictReport report = check(at(3, 1).build(), at(3, 2).discipline(D_NDEKSM).build(), half);

            assertThat(ofKind(report.soft(), ConflictKind.BROKEN_DAY)).singleElement().satisfies(conflict -> {
                assertThat(conflict.message()).isEqualTo(
                        "Рваний день у групи 4КІ1 (середа): чисельник — 3 пари з 1-ї, знаменник — 2 пари з 1-ї");
                assertThat(conflict.lessonIds()).containsExactly(half.id());
                assertThat(conflict.pair()).isEqualTo(3);
            });
        }

        @Test
        void differentFirstPairMakesABrokenDayAndTwoHalvesInOneSlotDoNot() {
            SlotLesson denominatorOnly = at(4, 1).parity(Parity.DENOMINATOR).discipline(D_KM).build();
            assertThat(ofKind(check(denominatorOnly, at(4, 2).build(), at(4, 3).discipline(D_NDEKSM).build()).soft(),
                    ConflictKind.BROKEN_DAY)).hasSize(1);

            SlotLesson numeratorHalf = at(4, 1).parity(Parity.NUMERATOR).discipline(D_FV).build();
            assertThat(ofKind(check(denominatorOnly, numeratorHalf, at(4, 2).build(),
                    at(4, 3).discipline(D_NDEKSM).build()).soft(), ConflictKind.BROKEN_DAY)).isEmpty();
        }

        @Test
        void moreThanFourPairsADayIsAnOverload() {
            ConflictFacts facts = facts().group(G_2KI1, "2КІ1", 2, 24, 2).build();
            ConflictReport report = check(facts,
                    at(1, 5).group(G_2KI1).build(), at(1, 6).group(G_2KI1).discipline(D_KM).build(),
                    at(1, 7).group(G_2KI1).discipline(D_NDEKSM).build(), at(1, 8).group(G_2KI1).discipline(D_FV).build(),
                    at(1, 4).group(G_2KI1).discipline(D_ENG).build());

            assertThat(ofKind(report.soft(), ConflictKind.DAY_OVERLOAD)).singleElement().satisfies(conflict -> {
                assertThat(conflict.message()).isEqualTo("Група 2КІ1 має 5 пар за день (понеділок)");
                assertThat(conflict.pair()).isEqualTo(8);
            });
        }

        @Test
        void sameDisciplineThreeTimesADayIsARepeat() {
            ConflictReport report = check(at(2, 1).build(), at(2, 2).build(), at(2, 3).build());

            assertThat(ofKind(report.soft(), ConflictKind.DISCIPLINE_REPEAT)).singleElement()
                    .satisfies(conflict -> assertThat(conflict.message())
                            .isEqualTo("СТП стоїть 3 рази за день у групи 4КІ1 (вівторок)"));
        }
    }

    @Nested
    class DatedLessons {

        @Test
        void datedConflictsCarryTheDate() {
            SlotLesson first = on(MONDAY, 2).teacher(T_KRENTSIN).build();
            SlotLesson second = on(MONDAY, 2).group(G_4KI2).teacher(T_KRENTSIN).build();

            assertThat(ofKind(check(first, second).hard(), ConflictKind.TEACHER)).singleElement()
                    .satisfies(conflict -> {
                        assertThat(conflict.date()).isEqualTo(MONDAY);
                        assertThat(conflict.weekday()).isNull();
                        assertThat(conflict.message()).endsWith("(19.10.2026, 2 пара)");
                    });
        }

        @Test
        void sameRulesApplyToDatesButNoBrokenDays() {
            ConflictReport report = check(on(MONDAY, 1).build(), on(MONDAY, 3).discipline(D_KM).build(),
                    on(MONDAY, 5).discipline(D_NDEKSM).build());

            assertThat(report.hard()).isEmpty();
            assertThat(report.soft()).extracting(ConflictDto::kind)
                    .containsExactlyInAnyOrder(ConflictKind.WINDOW, ConflictKind.WINDOW)
                    .doesNotContain(ConflictKind.BROKEN_DAY);
        }

        /** Owner's rule (round 7): a substitution or an extra pair may be on any pair 1–8, whatever the shift. */
        @Test
        void shiftWindowDoesNotApplyToDatedLessons() {
            assertThat(check(on(MONDAY, 5).build()).hard()).isEmpty();
            assertThat(check(on(MONDAY, 8).build()).hard()).isEmpty();
            assertThat(check(on(MONDAY, 1).group(G_2KI1).build()).hard()).isEmpty();
            // правило шаблону лишається: той самий слот у тижневому розкладі — жорсткий конфлікт
            assertThat(ofKind(check(at(1, 5).build()).hard(), ConflictKind.SHIFT)).hasSize(1);
        }

        @Test
        void lessonsOnDifferentDatesNeverClash() {
            assertThat(check(on(MONDAY, 2).teacher(T_KRENTSIN).build(),
                    on(MONDAY.plusWeeks(1), 2).group(G_4KI2).teacher(T_KRENTSIN).build()).hard()).isEmpty();
        }
    }

    @Test
    void reportIsOrderedAndCanBeFilteredByLesson() {
        SlotLesson early = at(1, 1).teacher(T_KRENTSIN).build();
        SlotLesson earlyClash = at(1, 1).group(G_4KI2).teacher(T_KRENTSIN).build();
        SlotLesson late = at(2, 3).room(R_304).build();
        SlotLesson lateClash = at(2, 3).group(G_4KI2).room(R_304).build();

        ConflictReport report = check(late, lateClash, early, earlyClash);

        assertThat(report.hard()).extracting(ConflictDto::weekday).containsExactly(1, 2);
        assertThat(report.involving(List.of(late.id())).hard()).extracting(ConflictDto::kind)
                .containsExactly(ConflictKind.ROOM);
        assertThat(report.hasHard()).isTrue();
    }
}
