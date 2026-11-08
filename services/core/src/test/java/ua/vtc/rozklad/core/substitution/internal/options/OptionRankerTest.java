package ua.vtc.rozklad.core.substitution.internal.options;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.vtc.rozklad.core.substitution.internal.options.RankingInputs.DATE;
import static ua.vtc.rozklad.core.substitution.internal.options.RankingInputs.ON_TRACK;
import static ua.vtc.rozklad.core.substitution.internal.options.RankingInputs.id;
import static ua.vtc.rozklad.core.substitution.internal.options.RankingInputs.lesson;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.accounting.AccountingStatus;
import ua.vtc.rozklad.core.substitution.PreferenceKind;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.GroupDiscipline;
import ua.vtc.rozklad.core.substitution.internal.options.RankingInput.Progress;

/** Ranking rules of DOMAIN §7, one by one. */
class OptionRankerTest {

    private static List<RankedOption> rank(RankingInputs input) {
        return OptionRanker.rank(input.build());
    }

    /** With some factors off, so that one factor can be read from the cost alone. */
    private static List<RankedOption> rankWithout(RankingInputs input, RankingFactor... disabled) {
        return OptionRanker.rank(input.build(), Set.of(disabled));
    }

    private static RankedOption cover(List<RankedOption> options, String teacher) {
        return options.stream().filter(option -> option.kind() == ChangeKind.REPLACE_DISCIPLINE
                && id(teacher).equals(option.teacherId())).findFirst().orElseThrow(() ->
                new AssertionError("no cover by " + teacher + " in " + options));
    }

    private static List<UUID> coverTeachers(List<RankedOption> options) {
        return options.stream().filter(option -> option.kind() == ChangeKind.REPLACE_DISCIPLINE)
                .map(RankedOption::teacherId).toList();
    }

    @Nested
    class Pool {

        @Test
        void neverOffersAnotherTeacherOfTheSameDisciplineNorAMakeUpPair() {
            List<RankedOption> options = rank(lesson(2).discipline("НДКС", "Непийвода").groupLesson(3, 0, "ОПЦЗ",
                    "Ганза", false));

            assertThat(options).extracting(RankedOption::kind)
                    .doesNotContain(ChangeKind.REPLACE_TEACHER, ChangeKind.ADDITIONAL)
                    .contains(ChangeKind.REPLACE_DISCIPLINE, ChangeKind.SWAP, ChangeKind.CANCEL);
        }

        @Test
        void theSameDisciplineIsNotACover() {
            List<RankedOption> options = rank(lesson(2).discipline(new GroupDiscipline(RankingInputs.STP, "СТП",
                    id("Непийвода"), "Непийвода", ON_TRACK, null, null)));

            assertThat(coverTeachers(options)).isEmpty();
        }

        @Test
        void aFinishedDisciplineOrOneWithoutAPairOfHoursLeftIsNotOffered() {
            Progress finished = new Progress(40, 40, 40, AccountingStatus.FINISHED, BigDecimal.TWO);
            Progress oneHourLeft = new Progress(40, 39, 39, AccountingStatus.ON_TRACK, BigDecimal.TWO);

            List<RankedOption> options = rank(lesson(2).discipline("НДКС", "Непийвода", finished)
                    .discipline("ОПЦЗ", "Ганза", oneHourLeft).discipline("ФВ", "Остапюк"));

            assertThat(coverTeachers(options)).containsExactly(id("Остапюк"));
        }

        @Test
        void busyAbsentUnavailableOrDecliningTeachersAreLeftOut() {
            List<RankedOption> options = rank(lesson(2).discipline("НДКС", "Непийвода").discipline("ОПЦЗ", "Ганза")
                    .discipline("ФВ", "Остапюк").discipline("ІКТ", "Вернигора").discipline("Матем.", "Бадунь")
                    .teaches("Непийвода", 2).absent("Ганза", 2).preference("Остапюк", PreferenceKind.UNAVAILABLE)
                    .declinedHere("Вернигора"));

            assertThat(coverTeachers(options)).containsExactly(id("Бадунь"));
        }

        @Test
        void aTeacherWhoDeclinedAnotherPairTodayCostsSixMore() {
            RankedOption option = cover(rank(lesson(2).discipline("НДКС", "Непийвода").declinedToday("Непийвода")),
                    "Непийвода");

            assertThat(option.cost()).isEqualTo(OptionRanker.DECLINED_TODAY);
            assertThat(option.reasons()).contains("Сьогодні вже відмовився(лась) від іншої пари");
        }

        @Test
        void oneTeacherWithTwoDisciplinesIsOneOptionWithTheCheaperOne() {
            Progress behind = new Progress(40, 10, 20, AccountingStatus.WILL_UNDERRUN, BigDecimal.TWO);

            List<RankedOption> options = rank(lesson(2).discipline("НДКС", "Непийвода")
                    .discipline("КМ", "Непийвода", behind).discipline("ОПЦЗ", "Ганза"));

            assertThat(coverTeachers(options)).containsOnlyOnce(id("Непийвода"));
            RankedOption option = cover(options, "Непийвода");
            assertThat(option.disciplineId()).isEqualTo(id("КМ"));
            assertThat(option.title()).isEqualTo("КМ — Непийвода");
            assertThat(option.reasons()).contains("Також веде: НДКС");
        }
    }

    @Nested
    class Costs {

        @Test
        void loadRankIsZeroForTheOnlyTeacherAndTiesAreAveraged() {
            assertThat(cover(rank(lesson(2).discipline("НДКС", "Непийвода").weekly("Непийвода", 30)), "Непийвода")
                    .cost()).isZero();

            List<RankedOption> options = rankWithout(lesson(2).discipline("НДКС", "Непийвода")
                    .discipline("ОПЦЗ", "Ганза").discipline("ФВ", "Остапюк").weekly("Непийвода", 8)
                    .weekly("Ганза", 20).weekly("Остапюк", 20), RankingFactor.BEHIND_PLAN);

            assertThat(cover(options, "Непийвода").cost()).isZero();
            assertThat(cover(options, "Непийвода").reasons())
                    .contains("Менше навантаження серед викладачів групи (8 год/тиж)");
            // ранги 1 і 2 однакові → (1 + 2) / 2 / 2 = 0.75 → 8
            assertThat(cover(options, "Ганза").cost()).isEqualTo(8);
            assertThat(cover(options, "Остапюк").cost()).isEqualTo(8);
        }

        @Test
        void coverBurdenIsNormalisedByLoadAndCappedAt12() {
            List<RankedOption> options = rankWithout(lesson(2).discipline("НДКС", "Непийвода")
                    .discipline("ОПЦЗ", "Ганза").weekly("Непийвода", 18).weekly("Ганза", 30)
                    .burden("Непийвода", 1.5, 2, DATE.minusDays(20)).burden("Ганза", 10, 10, DATE),
                    RankingFactor.BEHIND_PLAN);

            // 3 · 1.5 · 18 / 18 = 4.5 → 5 (округлення вгору), найменше навантаження → ранг 0
            assertThat(cover(options, "Непийвода").cost()).isEqualTo(5);
            assertThat(cover(options, "Непийвода").reasons()).contains("2 заміни за 6 тижнів");
            assertThat(cover(options, "Ганза").cost()).isEqualTo(OptionRanker.LOAD_MAX + OptionRanker.BURDEN_MAX);
        }

        @Test
        void aLowLoadTeacherCountsAsSixHoursAWeek() {
            RankedOption option = cover(rank(lesson(2).discipline("НДКС", "Непийвода").weekly("Непийвода", 2)
                    .burden("Непийвода", 1, 1, DATE)), "Непийвода");

            // 3 · 1 · 18 / max(6, 2) = 9
            assertThat(option.cost()).isEqualTo(9);
        }

        @Test
        void waitingBetweenPairsCostsTwicePerExtraEmptyPairUpToEightButNotOnline() {
            assertThat(cover(rank(lesson(2).discipline("НДКС", "Непийвода").teaches("Непийвода", 3)), "Непийвода")
                    .cost()).isZero();
            RankedOption twoEmpty = cover(rank(lesson(1).discipline("НДКС", "Непийвода").teaches("Непийвода", 4)),
                    "Непийвода");
            assertThat(twoEmpty.cost()).isEqualTo(2);
            assertThat(twoEmpty.reasons()).contains("Чекатиме 2 пари між заняттями");
            assertThat(cover(rank(lesson(1).discipline("НДКС", "Непийвода").teaches("Непийвода", 6)), "Непийвода")
                    .cost()).isEqualTo(6);
            assertThat(cover(rank(lesson(1).discipline("НДКС", "Непийвода").teaches("Непийвода", 8)), "Непийвода")
                    .cost()).isEqualTo(OptionRanker.WAITING_MAX);
            assertThat(cover(rank(lesson(1).online().discipline("НДКС", "Непийвода").teaches("Непийвода", 6)),
                    "Непийвода").cost()).isZero();
        }

        @Test
        void closingAWindowIsABonus() {
            RankedOption tight = cover(rank(lesson(3).discipline("НДКС", "Непийвода").teaches("Непийвода", 2, 4)),
                    "Непийвода");
            RankedOption loose = cover(rank(lesson(3).discipline("НДКС", "Непийвода").teaches("Непийвода", 1, 4)),
                    "Непийвода");

            assertThat(tight.cost()).isEqualTo(-OptionRanker.CLOSES_WINDOW_TIGHT);
            assertThat(tight.reasons().getFirst()).isEqualTo("Закриває вікно в розкладі викладача");
            assertThat(loose.cost()).isEqualTo(-OptionRanker.CLOSES_WINDOW_LOOSE);
        }

        @Test
        void thereIsNoDailyCapOnlyAnInfoChip() {
            RankedOption seventh = cover(rank(lesson(7).discipline("НДКС", "Непийвода")
                    .teaches("Непийвода", 1, 2, 3, 4, 5, 6)), "Непийвода");

            assertThat(seventh.band()).isEqualTo(Band.CLEAN);
            assertThat(seventh.warnings()).isEmpty();
            assertThat(seventh.reasons()).contains("Буде 7-ма пара за день", "7 пар поспіль");
            assertThat(seventh.cost()).isEqualTo(OptionRanker.LONG_RUN);
        }

        @Test
        void overrunIsAWarningThatMovesTheOptionToCaution() {
            Progress full = new Progress(40, 30, 40, AccountingStatus.ON_TRACK, BigDecimal.TWO);

            RankedOption option = cover(rank(lesson(2).discipline("НДКС", "Непийвода", full)), "Непийвода");

            assertThat(option.cost()).isEqualTo(OptionRanker.OVERRUN);
            assertThat(option.warnings()).containsExactly("НДКС піде понад план");
            assertThat(option.band()).isEqualTo(Band.CAUTION);
        }

        @Test
        void aDisciplineBehindPlanGetsPriorityByRelativeLagInsideThePool() {
            Progress far = new Progress(40, 10, 20, AccountingStatus.WILL_UNDERRUN, BigDecimal.TWO);
            Progress near = new Progress(40, 10, 36, AccountingStatus.WILL_UNDERRUN, BigDecimal.TWO);

            List<RankedOption> options = rankWithout(lesson(2).discipline("НДКС", "Непийвода", far)
                    .discipline("ОПЦЗ", "Ганза", near).discipline("ФВ", "Остапюк"), RankingFactor.LOAD);

            assertThat(cover(options, "Непийвода").cost()).isEqualTo(-10);
            assertThat(cover(options, "Непийвода").reasons().getFirst()).isEqualTo("НДКС відстає від плану");
            assertThat(cover(options, "Ганза").cost()).isEqualTo(-5);
            assertThat(cover(options, "Остапюк").cost()).isZero();
            assertThat(cover(options, "Остапюк").reasons()).noneMatch(reason -> reason.contains("відстає"));
            assertThat(coverTeachers(options)).containsExactly(id("Непийвода"), id("Ганза"), id("Остапюк"));
        }

        @Test
        void takingTheAbsentTeachersNextUndecidedPairToo() {
            List<RankedOption> options = rankWithout(lesson(2).discipline("НДКС", "Непийвода")
                    .discipline("ОПЦЗ", "Ганза").absentTeachersLesson(3, false).teaches("Ганза", 3),
                    RankingFactor.LOAD, RankingFactor.BEHIND_PLAN);
            RankedOption decidedNext = cover(rank(lesson(2).discipline("НДКС", "Непийвода")
                    .absentTeachersLesson(3, true)), "Непийвода");

            assertThat(cover(options, "Непийвода").cost()).isEqualTo(-OptionRanker.BUNDLE);
            assertThat(cover(options, "Непийвода").reasons()).contains("Може взяти й 3 пару");
            assertThat(cover(options, "Ганза").reasons()).noneMatch(reason -> reason.startsWith("Може взяти"));
            assertThat(decidedNext.cost()).isZero();
        }

        @Test
        void aPairWithThisGroupTodayIsAWarning() {
            RankedOption option = cover(rank(lesson(2).discipline("НДКС", "Непийвода")
                    .groupLesson(4, 0, "НДКС", "Непийвода", false)), "Непийвода");

            assertThat(option.warnings()).containsExactly("Вже має пару з цією групою (4 пара) — краще перестановка");
            assertThat(option.band()).isEqualTo(Band.CAUTION);
        }

        @Test
        void unwantedSlotAndRoomTypeAreWarnings() {
            GroupDiscipline lab = new GroupDiscipline(id("Інф."), "Інф.", id("Вернигора"), "Вернигора", ON_TRACK,
                    RoomType.COMPUTER_LAB, null);
            GroupDiscipline labWithOwnRoom = new GroupDiscipline(id("Інф."), "Інф.", id("Вернигора"), "Вернигора",
                    ON_TRACK, RoomType.COMPUTER_LAB, id("room 21"));

            RankedOption unwanted = cover(rank(lesson(2).discipline("НДКС", "Непийвода")
                    .preference("Непийвода", PreferenceKind.UNWANTED)), "Непийвода");
            RankedOption wrongRoom = cover(rank(lesson(2).discipline(lab)), "Вернигора");
            RankedOption ownRoom = cover(rank(lesson(2).discipline(labWithOwnRoom)), "Вернигора");

            assertThat(unwanted.cost()).isEqualTo(OptionRanker.UNWANTED);
            assertThat(unwanted.warnings()).containsExactly("Небажаний час (побажання викладача)");
            assertThat(wrongRoom.cost()).isEqualTo(OptionRanker.ROOM_TYPE);
            assertThat(wrongRoom.warnings()).containsExactly("Потрібна аудиторія «Комп'ютерний клас»");
            assertThat(ownRoom.warnings()).isEmpty();
            assertThat(ownRoom.roomId()).isEqualTo(id("room 21"));
        }
    }

    @Nested
    class OtherKinds {

        @Test
        void swapOfTheLastPairIsCleanAndOfAMiddlePairLeavesAWindow() {
            List<RankedOption> options = rank(lesson(2).today(DATE).groupLesson(1, 0, "ОПЦЗ", "Ганза", false)
                    .groupLesson(3, 0, "НДКС", "Непийвода", false).groupLesson(4, 0, "ФВ", "Остапюк", false));

            assertThat(options).filteredOn(option -> option.kind() == ChangeKind.SWAP)
                    .extracting(RankedOption::title, RankedOption::band)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("Переставити 4 пару (ФВ) на 2", Band.CLEAN),
                            org.assertj.core.groups.Tuple.tuple("Переставити 3 пару (НДКС) на 2", Band.CAUTION));
            assertThat(options).filteredOn(option -> option.title().startsWith("Переставити 3")).singleElement()
                    .satisfies(swap -> assertThat(swap.warnings()).containsExactly("У групи з'явиться вікно на 3 парі"));
        }

        @Test
        void anEarlierPairMovesOnlyForAFutureDay() {
            RankingInputs today = lesson(3).today(DATE).groupLesson(1, 0, "ОПЦЗ", "Ганза", false);
            RankingInputs tomorrow = lesson(3).today(DATE.minusDays(1)).groupLesson(1, 0, "ОПЦЗ", "Ганза", false);

            assertThat(rank(today)).noneMatch(option -> option.kind() == ChangeKind.SWAP);
            assertThat(rank(tomorrow)).filteredOn(option -> option.kind() == ChangeKind.SWAP).singleElement()
                    .satisfies(swap -> {
                        assertThat(swap.reasons()).contains("Група прийде пізніше");
                        assertThat(swap.band()).isEqualTo(Band.CLEAN);
                    });
        }

        @Test
        void otherSubgroupsTeacherOfTheSameDisciplineMayTakeBothSubgroups() {
            List<RankedOption> options = rank(lesson(2).subgroup(1).groupLesson(2, 2, "СТП", "Можаровська", false));

            assertThat(options).filteredOn(option -> option.kind() == ChangeKind.MERGE_SUBGROUPS).singleElement()
                    .satisfies(merge -> {
                        assertThat(merge.title()).isEqualTo("Об'єднати підгрупи: СТП — Можаровська");
                        assertThat(merge.teacherId()).isEqualTo(id("Можаровська"));
                        assertThat(merge.swapLessonId()).isEqualTo(id("СТП22"));
                        assertThat(merge.roomId()).isEqualTo(id("room2"));
                        assertThat(merge.band()).isEqualTo(Band.CLEAN);
                    });
        }

        @Test
        void noMergeForAWholeGroupLessonAThirdCohortOrAnAbsentPartner() {
            assertThat(rank(lesson(2).groupLesson(2, 2, "СТП", "Можаровська", false)))
                    .noneMatch(option -> option.kind() == ChangeKind.MERGE_SUBGROUPS);
            assertThat(rank(lesson(2).subgroup(1).groupLesson(2, 2, "СТП", "Можаровська", false)
                    .groupLesson(2, 3, "ФВ", "Остапюк", false)))
                    .noneMatch(option -> option.kind() == ChangeKind.MERGE_SUBGROUPS);
            assertThat(rank(lesson(2).subgroup(1).groupLesson(2, 2, "СТП", "Можаровська", false)
                    .absent("Можаровська", 2))).noneMatch(option -> option.kind() == ChangeKind.MERGE_SUBGROUPS);
            assertThat(rank(lesson(2).subgroup(1).groupLesson(2, 2, "Інф.", "Вернигора", false)))
                    .noneMatch(option -> option.kind() == ChangeKind.MERGE_SUBGROUPS);
        }

        @Test
        void cancelOfAnEdgePairIsEasyWhenOnlineOrBlockOrHoursSuffice() {
            Progress enough = new Progress(40, 10, 40, AccountingStatus.ON_TRACK, BigDecimal.TWO);

            assertThat(cancel(lesson(1).groupLesson(2, 0, "ОПЦЗ", "Ганза", false))).satisfies(cancel -> {
                assertThat(cancel.band()).isEqualTo(Band.LAST);
                assertThat(cancel.reasons()).containsExactly("Перша пара: група прийде пізніше");
            });
            assertThat(cancel(lesson(1).online().groupLesson(2, 0, "ОПЦЗ", "Ганза", false)).band())
                    .isEqualTo(Band.EASY_CANCEL);
            assertThat(cancel(lesson(3).block().groupLesson(2, 0, "ОПЦЗ", "Ганза", false))).satisfies(cancel -> {
                assertThat(cancel.band()).isEqualTo(Band.EASY_CANCEL);
                assertThat(cancel.reasons()).containsExactly("Остання пара: група піде раніше", "У групи сьогодні блок");
            });
            assertThat(cancel(lesson(2).targetProgress(enough))).satisfies(cancel -> {
                assertThat(cancel.band()).isEqualTo(Band.EASY_CANCEL);
                assertThat(cancel.reasons()).containsExactly("Єдина пара групи цього дня", "Годин з СТП вистачає");
            });
        }

        @Test
        void cancelOfAMiddlePairIsLast() {
            RankedOption cancel = cancel(lesson(2).online().groupLesson(1, 0, "ОПЦЗ", "Ганза", false)
                    .groupLesson(3, 0, "ФВ", "Остапюк", true));

            assertThat(cancel.band()).isEqualTo(Band.LAST);
            assertThat(cancel.warnings()).containsExactly("У групи з'явиться вікно");
        }

        private static RankedOption cancel(RankingInputs input) {
            return rank(input).stream().filter(option -> option.kind() == ChangeKind.CANCEL).findFirst().orElseThrow();
        }
    }

    @Nested
    class Order {

        @Test
        void bandFirstThenKindThenCostThenOldestCoverThenName() {
            Progress full = new Progress(40, 30, 40, AccountingStatus.ON_TRACK, BigDecimal.TWO);
            List<RankedOption> options = rank(lesson(2).today(DATE)
                    .discipline("Матем.", "Бадунь").discipline("ОПЦЗ", "Ганза").discipline("ФВ", "Остапюк", full)
                    .weekly("Бадунь", 10).weekly("Ганза", 10).weekly("Остапюк", 1)
                    .burden("Бадунь", 0.0, 0, DATE.minusDays(3))
                    .groupLesson(1, 0, "НДКС", "Непийвода", false).groupLesson(3, 0, "Інф.", "Вернигора", false));

            assertThat(options).extracting(RankedOption::title).containsExactly(
                    "ОПЦЗ — Ганза", "Матем. — Бадунь", "Переставити 3 пару (Інф.) на 2", "ФВ — Остапюк",
                    "Пари не буде");
            assertThat(options).extracting(RankedOption::band).containsExactly(Band.CLEAN, Band.CLEAN, Band.CLEAN,
                    Band.CAUTION, Band.LAST);
        }

        @Test
        void onlyAClearlyBestCleanOptionIsRecommendedAndNeverForAPastLesson() {
            RankingInputs clear = lesson(2).discipline("НДКС", "Непийвода").teaches("Непийвода", 1, 3);
            RankingInputs close = lesson(2).discipline("НДКС", "Непийвода").discipline("ОПЦЗ", "Ганза");

            assertThat(rank(clear).getFirst().recommended()).isTrue();
            assertThat(rank(clear).stream().skip(1)).noneMatch(RankedOption::recommended);
            assertThat(rank(close)).noneMatch(RankedOption::recommended);
            assertThat(rank(clear.today(DATE.plusDays(1)))).noneMatch(RankedOption::recommended);
        }

        @Test
        void scoreOnlyReflectsThePosition() {
            List<RankedOption> options = rank(lesson(2).discipline("НДКС", "Непийвода").discipline("ОПЦЗ", "Ганза")
                    .groupLesson(3, 0, "ФВ", "Остапюк", false));

            assertThat(options).extracting(RankedOption::score).containsExactly(
                    IntStream.range(0, options.size()).map(index -> Math.max(0, 100 - 10 * index)).boxed()
                            .toArray(Integer[]::new));
        }

        @Test
        void shuffledInputsGiveTheSameRanking() {
            RankingInput input = lesson(3).discipline("Матем.", "Бадунь").discipline("ОПЦЗ", "Ганза")
                    .discipline("ФВ", "Остапюк").discipline("КМ", "Ганза").weekly("Бадунь", 12).weekly("Ганза", 12)
                    .groupLesson(1, 0, "НДКС", "Непийвода", false).groupLesson(4, 0, "Інф.", "Вернигора", false)
                    .absentTeachersLesson(2, false).teaches("Остапюк", 2, 4).build();
            List<RankedOption> expected = OptionRanker.rank(input);

            Random random = new Random(42);
            for (int round = 0; round < 20; round++) {
                List<RankingInput.GroupDiscipline> pool = new ArrayList<>(input.groupDisciplines());
                List<RankingInput.GroupLesson> day = new ArrayList<>(input.groupDay());
                Collections.shuffle(pool, random);
                Collections.shuffle(day, random);
                Map<java.util.UUID, java.util.List<Integer>> pairs = new HashMap<>(input.teacherPairsThatDay());
                RankingInput shuffled = new RankingInput(input.target(), day, pool, pairs, input.busyByPair(),
                        input.absentByPair(), input.slotPreferences(), input.weeklyHours(), input.coverBurden(),
                        input.coverCount(), input.lastCover(), input.declinedThisLesson(), input.declinedToday(),
                        Map.of());
                assertThat(OptionRanker.rank(shuffled)).isEqualTo(expected);
            }
        }

        @Test
        void switchingAFactorOffOnlyRemovesItsCost() {
            Progress far = new Progress(40, 10, 20, AccountingStatus.WILL_UNDERRUN, BigDecimal.TWO);
            RankingInput input = lesson(2).discipline("НДКС", "Непийвода", far).discipline("ОПЦЗ", "Ганза").build();

            assertThat(cover(OptionRanker.rank(input, Set.of(RankingFactor.LOAD, RankingFactor.BEHIND_PLAN)),
                    "Непийвода").cost()).isZero();
            assertThat(cover(OptionRanker.rank(input, Set.of(RankingFactor.LOAD)), "Непийвода").cost()).isEqualTo(-10);
        }
    }
}
