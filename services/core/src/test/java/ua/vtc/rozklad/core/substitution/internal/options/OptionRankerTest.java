package ua.vtc.rozklad.core.substitution.internal.options;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.substitution.PreferenceKind;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.Candidate;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.GroupDiscipline;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.GroupLesson;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.MakeUpSlot;

/** Scoring rules of DOMAIN §7, one by one. */
class OptionRankerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 19);
    private static final UUID LESSON = UUID.randomUUID();
    private static final UUID STP = UUID.randomUUID();
    private static final UUID NDKS = UUID.randomUUID();
    private static final UUID COMMISSION = UUID.randomUUID();
    private static final Candidate ABSENT = new Candidate(UUID.randomUUID(), "Кренцін М.Д.", COMMISSION);
    private static final Candidate PROKOPCHUK = new Candidate(UUID.randomUUID(), "Прокопчук О.В.", UUID.randomUUID());
    private static final Candidate NEPYIVODA = new Candidate(UUID.randomUUID(), "Непийвода М.В.", UUID.randomUUID());

    @Nested
    class ReplaceTeacher {

        @Test
        void teacherOfTheDisciplineGets50() {
            RankedOption option = only(Input.lesson(3).competent(PROKOPCHUK).build(), PROKOPCHUK);

            assertThat(option.rawScore()).isEqualTo(50);
            assertThat(option.reasons()).containsExactly("Веде цю дисципліну");
            assertThat(option.title()).isEqualTo("Замінити на Прокопчук О.В.");
        }

        @Test
        void sameCycleCommissionGets20() {
            Candidate colleague = new Candidate(UUID.randomUUID(), "Ганза І.П.", COMMISSION);

            RankedOption option = only(Input.lesson(3).teachers(colleague).build(), colleague);

            assertThat(option.rawScore()).isEqualTo(20);
            assertThat(option.reasons()).containsExactly("Та сама циклова комісія");
        }

        @Test
        void closingAWindowGets15AndHavingLessonsThatDay10() {
            RankedOption option = only(Input.lesson(3).dayPairs(PROKOPCHUK, 2, 4).build(), PROKOPCHUK);

            assertThat(option.rawScore()).isEqualTo(25);
            assertThat(option.reasons()).containsExactly("Закриває вікно між 2 і 4 парою", "Вже має 2 пари цього дня");
        }

        @Test
        void lessonsOnlyBeforeTheSlotDoNotCloseAWindow() {
            RankedOption option = only(Input.lesson(3).dayPairs(PROKOPCHUK, 1).build(), PROKOPCHUK);

            assertThat(option.rawScore()).isEqualTo(10);
            assertThat(option.reasons()).containsExactly("Вже має 1 пару цього дня");
        }

        @Test
        void everySubstitutionThisMonthCosts8() {
            RankedOption option = only(Input.lesson(3).competent(PROKOPCHUK).substitutions(PROKOPCHUK, 1).build(),
                    PROKOPCHUK);

            assertThat(option.rawScore()).isEqualTo(42);
            assertThat(option.reasons()).containsExactly("Веде цю дисципліну", "1 заміна цього місяця");
        }

        @Test
        void substitutionPenaltyStopsAt24() {
            RankedOption three = only(Input.lesson(3).competent(PROKOPCHUK).substitutions(PROKOPCHUK, 3).build(),
                    PROKOPCHUK);
            RankedOption five = only(Input.lesson(3).competent(PROKOPCHUK).substitutions(PROKOPCHUK, 5).build(),
                    PROKOPCHUK);

            assertThat(three.rawScore()).isEqualTo(26);
            assertThat(three.reasons()).contains("3 заміни цього місяця");
            assertThat(five.rawScore()).isEqualTo(26);
            assertThat(five.reasons()).contains("5 замін цього місяця");
        }

        @Test
        void unwantedSlotCosts15AndWarns() {
            RankedOption option = only(Input.lesson(3).competent(PROKOPCHUK)
                    .preference(PROKOPCHUK, PreferenceKind.UNWANTED).build(), PROKOPCHUK);

            assertThat(option.rawScore()).isEqualTo(35);
            assertThat(option.warnings()).containsExactly("Небажаний час (побажання викладача)");
        }

        @Test
        void displayedScoreStaysWithin0And100() {
            RankedOption option = only(Input.lesson(3).substitutions(PROKOPCHUK, 3)
                    .preference(PROKOPCHUK, PreferenceKind.UNWANTED).build(), PROKOPCHUK);

            assertThat(option.rawScore()).isEqualTo(-39);
            assertThat(option.score()).isZero();
        }

        @Test
        void absentBusyAndUnavailableTeachersAreExcluded() {
            Candidate sick = new Candidate(UUID.randomUUID(), "Крисак Л.", null);
            Candidate busy = new Candidate(UUID.randomUUID(), "Ганза І.П.", null);
            Candidate unavailable = new Candidate(UUID.randomUUID(), "Масловата Н.", null);
            RankingInput input = Input.lesson(3)
                    .teachers(ABSENT, sick, busy, unavailable, PROKOPCHUK)
                    .absent(sick).busy(busy).preference(unavailable, PreferenceKind.UNAVAILABLE)
                    .build();

            assertThat(teacherOptions(input)).extracting(RankedOption::teacherId)
                    .containsExactly(PROKOPCHUK.id());
        }

        @Test
        void onlyTheTenBestCandidatesAreOffered() {
            Input builder = Input.lesson(3);
            IntStream.rangeClosed(1, 12).forEach(index ->
                    builder.teachers(new Candidate(UUID.randomUUID(), "Викладач " + index, null)));

            assertThat(teacherOptions(builder.build())).hasSize(OptionRanker.TEACHER_LIMIT);
        }

        @Test
        void equalScoresPreferFewerSubstitutionsThenTheName() {
            Candidate andriy = new Candidate(UUID.randomUUID(), "Андрієнко А.", null);
            Candidate boris = new Candidate(UUID.randomUUID(), "Бойко Б.", null);
            RankingInput input = Input.lesson(3).teachers(boris, andriy).competent(PROKOPCHUK)
                    .substitutions(PROKOPCHUK, 3).build();

            assertThat(teacherOptions(input)).extracting(RankedOption::teacherId)
                    .containsExactly(PROKOPCHUK.id(), andriy.id(), boris.id());
        }
    }

    @Nested
    class ReplaceDiscipline {

        @Test
        void disciplineBehindPlanGetsABonusWithTheMissingHours() {
            RankingInput input = Input.lesson(2)
                    .discipline(new GroupDiscipline(NDKS, "НДКС", NEPYIVODA.id(), NEPYIVODA.shortName(), 6, false, null))
                    .build();

            RankedOption option = optionsOf(input, ChangeKind.REPLACE_DISCIPLINE).getFirst();

            assertThat(option.rawScore()).isEqualTo(OptionRanker.DISCIPLINE_BASE + OptionRanker.BEHIND_PLAN);
            assertThat(option.reasons()).containsExactly("Веде в цій групі НДКС", "Відстає на 6 год");
            assertThat(option.title()).isEqualTo("Замінити на НДКС (Непийвода М.В.)");
            assertThat(option.disciplineId()).isEqualTo(NDKS);
            assertThat(option.teacherId()).isEqualTo(NEPYIVODA.id());
        }

        @Test
        void finishedSameOrBusyDisciplinesAreNotOffered() {
            Candidate busy = new Candidate(UUID.randomUUID(), "Ганза І.П.", null);
            RankingInput input = Input.lesson(2)
                    .discipline(new GroupDiscipline(NDKS, "НДКС", NEPYIVODA.id(), NEPYIVODA.shortName(), 0, true, null))
                    .discipline(new GroupDiscipline(STP, "СТП", PROKOPCHUK.id(), PROKOPCHUK.shortName(), 0, false, null))
                    .discipline(new GroupDiscipline(UUID.randomUUID(), "ОПЦЗ", busy.id(), busy.shortName(), 0, false,
                            null))
                    .busy(busy)
                    .build();

            assertThat(optionsOf(input, ChangeKind.REPLACE_DISCIPLINE)).isEmpty();
        }

        @Test
        void requiredRoomTypeIsAWarning() {
            RankingInput input = Input.lesson(2)
                    .discipline(new GroupDiscipline(NDKS, "НДКС", NEPYIVODA.id(), NEPYIVODA.shortName(), 0, false,
                            RoomType.LAB))
                    .build();

            assertThat(optionsOf(input, ChangeKind.REPLACE_DISCIPLINE).getFirst().warnings())
                    .containsExactly("Потрібна аудиторія типу «Лабораторія»");
        }
    }

    @Nested
    class Swap {

        @Test
        void movingTheLastPairLetsTheGroupLeaveEarlier() {
            UUID fourth = UUID.randomUUID();
            RankingInput input = Input.lesson(2)
                    .groupLesson(new GroupLesson(UUID.randomUUID(), 1, 0, UUID.randomUUID(), "БЖ", PROKOPCHUK.id(),
                            PROKOPCHUK.shortName(), false))
                    .groupLesson(new GroupLesson(fourth, 4, 0, NDKS, "НДКС", NEPYIVODA.id(), NEPYIVODA.shortName(),
                            false))
                    .build();

            RankedOption option = optionsOf(input, ChangeKind.SWAP).getFirst();

            assertThat(option.title()).isEqualTo("Переставити 4 пару на 2: група піде раніше");
            assertThat(option.rawScore()).isEqualTo(OptionRanker.SWAP_BASE + OptionRanker.SWAP_LAST_PAIR);
            assertThat(option.swapLessonId()).isEqualTo(fourth);
            assertThat(option.teacherId()).isEqualTo(NEPYIVODA.id());
        }

        @Test
        void movingAMiddlePairLeavesAWindow() {
            RankingInput input = Input.lesson(2)
                    .groupLesson(new GroupLesson(UUID.randomUUID(), 4, 0, NDKS, "НДКС", NEPYIVODA.id(),
                            NEPYIVODA.shortName(), false))
                    .groupLesson(new GroupLesson(UUID.randomUUID(), 5, 0, UUID.randomUUID(), "БЖ", UUID.randomUUID(),
                            "Погосян А.", false))
                    .build();

            RankedOption fromFourth = optionsOf(input, ChangeKind.SWAP).stream()
                    .filter(option -> option.title().startsWith("Переставити 4")).findFirst().orElseThrow();

            assertThat(fromFourth.rawScore()).isEqualTo(OptionRanker.SWAP_BASE - OptionRanker.SWAP_LEAVES_WINDOW);
            assertThat(fromFourth.warnings()).containsExactly("У групи з'явиться вікно на 4 парі");
        }

        @Test
        void earlierDecidedOrBusyLessonsCannotBeMoved() {
            Candidate busy = new Candidate(UUID.randomUUID(), "Ганза І.П.", null);
            RankingInput input = Input.lesson(3)
                    .groupLesson(new GroupLesson(UUID.randomUUID(), 1, 0, NDKS, "НДКС", NEPYIVODA.id(),
                            NEPYIVODA.shortName(), false))
                    .groupLesson(new GroupLesson(UUID.randomUUID(), 4, 0, NDKS, "НДКС", NEPYIVODA.id(),
                            NEPYIVODA.shortName(), true))
                    .groupLesson(new GroupLesson(UUID.randomUUID(), 5, 0, NDKS, "ОПЦЗ", busy.id(), busy.shortName(),
                            false))
                    .busy(busy)
                    .build();

            assertThat(optionsOf(input, ChangeKind.SWAP)).isEmpty();
        }
    }

    @Nested
    class Cancel {

        @Test
        void firstPairMeansTheGroupComesLater() {
            RankingInput input = Input.lesson(1).groupLesson(lessonAt(2)).groupLesson(lessonAt(3)).build();

            RankedOption cancel = optionsOf(input, ChangeKind.CANCEL).getFirst();

            assertThat(cancel.title()).isEqualTo("Пари не буде");
            assertThat(cancel.rawScore()).isEqualTo(OptionRanker.CANCEL_BASE + OptionRanker.CANCEL_EDGE_PAIR);
            assertThat(cancel.reasons()).containsExactly("Перша пара: група прийде пізніше");
        }

        @Test
        void lastPairMeansTheGroupLeavesEarlier() {
            RankingInput input = Input.lesson(4).groupLesson(lessonAt(2)).build();

            assertThat(optionsOf(input, ChangeKind.CANCEL).getFirst().reasons())
                    .containsExactly("Остання пара: група піде раніше");
        }

        @Test
        void middlePairLeavesAWindow() {
            RankingInput input = Input.lesson(3).groupLesson(lessonAt(2)).groupLesson(lessonAt(4)).build();

            RankedOption cancel = optionsOf(input, ChangeKind.CANCEL).getFirst();

            assertThat(cancel.rawScore()).isEqualTo(OptionRanker.CANCEL_BASE - OptionRanker.CANCEL_MIDDLE_PAIR);
            assertThat(cancel.warnings()).containsExactly("У групи з'явиться вікно");
        }

        @Test
        void otherSubgroupDoesNotCountAsAPair() {
            RankingInput input = Input.lesson(3).subgroup(1)
                    .groupLesson(new GroupLesson(UUID.randomUUID(), 4, 2, NDKS, "НДКС", NEPYIVODA.id(), "Н.", false))
                    .build();

            assertThat(optionsOf(input, ChangeKind.CANCEL).getFirst().reasons())
                    .containsExactly("Єдина пара групи цього дня");
        }
    }

    @Test
    void makeUpSlotsBecomeAdditionalOptionsOfTheAbsentTeacher() {
        RankingInput input = Input.lesson(2).makeUp(new MakeUpSlot(LocalDate.of(2026, 10, 21), 5)).build();

        RankedOption makeUp = optionsOf(input, ChangeKind.ADDITIONAL).getFirst();

        assertThat(makeUp.title()).isEqualTo("Відпрацювання: 21 жовтня, 5 пара");
        assertThat(makeUp.teacherId()).isEqualTo(ABSENT.id());
        assertThat(makeUp.disciplineId()).isEqualTo(STP);
        assertThat(makeUp.date()).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(makeUp.pair()).isEqualTo(5);
    }

    @Test
    void allOptionsAreRankedBestFirst() {
        RankingInput input = Input.lesson(2)
                .competent(PROKOPCHUK).dayPairs(PROKOPCHUK, 1, 3)
                .discipline(new GroupDiscipline(NDKS, "НДКС", NEPYIVODA.id(), NEPYIVODA.shortName(), 6, false, null))
                .groupLesson(lessonAt(1))
                .makeUp(new MakeUpSlot(LocalDate.of(2026, 10, 21), 5))
                .build();

        List<RankedOption> ranked = OptionRanker.rank(input);

        assertThat(ranked).extracting(RankedOption::kind).startsWith(ChangeKind.REPLACE_TEACHER,
                ChangeKind.REPLACE_DISCIPLINE);
        assertThat(ranked).isSortedAccordingTo((first, second) -> Integer.compare(second.rawScore(), first.rawScore()));
        assertThat(ranked.getFirst().rawScore()).isEqualTo(75);
    }

    private static RankedOption only(RankingInput input, Candidate teacher) {
        return teacherOptions(input).stream().filter(option -> option.teacherId().equals(teacher.id()))
                .findFirst().orElseThrow();
    }

    private static List<RankedOption> teacherOptions(RankingInput input) {
        return optionsOf(input, ChangeKind.REPLACE_TEACHER);
    }

    private static List<RankedOption> optionsOf(RankingInput input, ChangeKind kind) {
        return OptionRanker.rank(input).stream().filter(option -> option.kind() == kind).toList();
    }

    private static GroupLesson lessonAt(int pair) {
        return new GroupLesson(UUID.randomUUID(), pair, 0, UUID.randomUUID(), "Ін. мова", UUID.randomUUID(),
                "Крисак Л.", false);
    }

    /** Test data builder: an absent teacher's lesson of СТП and nothing else unless added. */
    private static final class Input {

        private final int pair;
        private int subgroup;
        private final List<Candidate> teachers = new ArrayList<>(List.of(ABSENT, PROKOPCHUK));
        private final Set<UUID> competent = new HashSet<>();
        private final Map<UUID, List<Integer>> dayPairs = new HashMap<>();
        private final Set<UUID> busy = new HashSet<>();
        private final Set<UUID> absent = new HashSet<>(Set.of(ABSENT.id()));
        private final Map<UUID, PreferenceKind> preferences = new HashMap<>();
        private final Map<UUID, Integer> substitutions = new HashMap<>();
        private final List<GroupLesson> groupDay = new ArrayList<>();
        private final List<GroupDiscipline> disciplines = new ArrayList<>();
        private final List<MakeUpSlot> makeUp = new ArrayList<>();

        private Input(int pair) {
            this.pair = pair;
        }

        static Input lesson(int pair) {
            return new Input(pair);
        }

        Input subgroup(int value) {
            this.subgroup = value;
            return this;
        }

        Input teachers(Candidate... candidates) {
            for (Candidate candidate : candidates) {
                if (!teachers.contains(candidate)) {
                    teachers.add(candidate);
                }
            }
            return this;
        }

        Input competent(Candidate teacher) {
            teachers(teacher);
            competent.add(teacher.id());
            return this;
        }

        Input dayPairs(Candidate teacher, Integer... pairs) {
            dayPairs.put(teacher.id(), List.of(pairs));
            return this;
        }

        Input busy(Candidate teacher) {
            busy.add(teacher.id());
            return this;
        }

        Input absent(Candidate teacher) {
            absent.add(teacher.id());
            return this;
        }

        Input preference(Candidate teacher, PreferenceKind kind) {
            preferences.put(teacher.id(), kind);
            return this;
        }

        Input substitutions(Candidate teacher, int count) {
            substitutions.put(teacher.id(), count);
            return this;
        }

        Input groupLesson(GroupLesson lesson) {
            groupDay.add(lesson);
            return this;
        }

        Input discipline(GroupDiscipline discipline) {
            disciplines.add(discipline);
            return this;
        }

        Input makeUp(MakeUpSlot slot) {
            makeUp.add(slot);
            return this;
        }

        RankingInput build() {
            return new RankingInput(
                    new RankingInput.Target(LESSON, DATE, pair, subgroup, STP, ABSENT.id(), COMMISSION,
                            RoomType.COMPUTER_LAB),
                    teachers, competent, dayPairs, busy, absent, preferences, substitutions, groupDay, disciplines,
                    makeUp);
        }
    }
}
