package ua.vtc.rozklad.core.timetable.internal.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.contracts.events.ProblemSnapshot;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;

/**
 * B9: the load is checked against the grid before a job is stored. Windows are the DOMAIN defaults:
 * shift 1 pairs 1–4 Monday–Friday (20 slots), shift 2 pairs 5–8 Monday–Thursday and 4–6 on Friday (19).
 */
class LoadFitCheckTest {

    private static final UUID TK = UUID.randomUUID();
    private static final UUID KI = UUID.randomUUID();
    private static final UUID TEACHER = UUID.randomUUID();
    private static final UUID DISCIPLINE = UUID.randomUUID();
    private static final UUID GYM = UUID.randomUUID();

    @Test
    void eighteenAndAHalfPairsFitNineteenSlots() {
        List<ProblemSnapshot.Unit> units = new ArrayList<>(units(TK, 0, 18, 1, null, null));

        assertThat(LoadFitCheck.check(snapshot(units, List.of())).problems()).isEmpty();
    }

    @Test
    void aSecondShiftGroupWithTwentyPairsDoesNotFit() {
        List<ProblemSnapshot.Unit> units = new ArrayList<>(units(TK, 0, 20, 1, null, null));

        LoadFitCheck.Result result = LoadFitCheck.check(snapshot(units, List.of()));

        assertThat(result.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.kind()).isEqualTo(LoadFitCheck.Kind.GROUP);
            assertThat(problem.label()).isEqualTo("2ТК");
            assertThat(problem.requiredPairs()).isEqualTo(20.5);
            assertThat(problem.availablePairs()).isEqualTo(19);
            assertThat(problem.message()).isEqualTo("Група 2ТК: 20,5 пари на тиждень, а в її зміні 19 слотів.");
        });
        assertThatThrownBy(result::throwIfAny).isInstanceOfSatisfying(BusinessRuleException.class, error -> {
            assertThat(error.getCode()).isEqualTo("GENERATION_LOAD_EXCEEDS_SLOTS");
            assertThat(error.getMessage()).startsWith("Навантаження не вміщується в сітку занять (1).");
            assertThat(error.getProperties()).containsKey("problems");
        });
    }

    /** Parallel subgroups share a slot: whole-group pairs count once with each subgroup's own pairs. */
    @Test
    void eachSubgroupIsCheckedWithTheWholeGroupPairs() {
        List<ProblemSnapshot.Unit> units = new ArrayList<>(units(KI, 0, 18, 0, null, null));
        units.addAll(units(KI, 1, 2, 0, null, null));
        units.addAll(units(KI, 2, 3, 0, null, null));

        assertThat(LoadFitCheck.check(snapshot(units, List.of())).problems())
                .extracting(LoadFitCheck.Problem::label, LoadFitCheck.Problem::requiredPairs)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("4КІ1, підгрупа 2", 21.0));
    }

    @Test
    void aTeacherCannotTeachMorePairsThanTheFreeSlotsOfTheirShifts() {
        List<ProblemSnapshot.Unit> units = new ArrayList<>(units(KI, 0, 10, 0, TEACHER, null));
        units.addAll(units(TK, 0, 9, 0, TEACHER, null));
        List<ProblemSnapshot.Timeslot> unavailable = new ArrayList<>();
        for (int weekday = 1; weekday <= 5; weekday++) {
            unavailable.add(new ProblemSnapshot.Timeslot(weekday, 1));
            unavailable.add(new ProblemSnapshot.Timeslot(weekday, 2));
            unavailable.add(new ProblemSnapshot.Timeslot(weekday, 3));
        }

        LoadFitCheck.Result result = LoadFitCheck.check(snapshot(units, unavailable));

        // зміни 1 і 2 разом — 38 слотів (пʼятнична 4-та пара спільна), мінус 15 недоступних = 23; 19 пар вміщуються
        assertThat(result.problems()).isEmpty();
        units.addAll(units(TK, 0, 5, 0, TEACHER, null));
        assertThat(LoadFitCheck.check(snapshot(units, unavailable)).problems()).singleElement()
                .satisfies(problem -> {
                    assertThat(problem.kind()).isEqualTo(LoadFitCheck.Kind.TEACHER);
                    assertThat(problem.availablePairs()).isEqualTo(23);
                    assertThat(problem.message()).isEqualTo(
                            "Викладач Кренцін М.Д.: 24 пари на тиждень, а вільних слотів у змінах його груп — 23.");
                });
    }

    @Test
    void theGymHasParallelPlacesOverItsGroupsWindows() {
        List<ProblemSnapshot.Unit> units = new ArrayList<>(units(KI, 0, 20, 0, null, RoomType.GYM));
        units.addAll(units(TK, 0, 0, 0, null, null));

        assertThat(LoadFitCheck.check(snapshot(units, List.of(), 1)).problems()).isEmpty();
        units.add(unit(KI, 1, UnitWeight.WHOLE, null, RoomType.GYM));
        assertThat(LoadFitCheck.check(snapshot(units, List.of(), 1)).problems())
                .extracting(LoadFitCheck.Problem::kind)
                .containsExactly(LoadFitCheck.Kind.GROUP, LoadFitCheck.Kind.ROOM_TYPE);
        assertThat(LoadFitCheck.check(snapshot(units, List.of(), 2)).problems())
                .extracting(LoadFitCheck.Problem::kind).containsExactly(LoadFitCheck.Kind.GROUP);
    }

    @Test
    void pairsAreWrittenInUkrainian() {
        assertThat(LoadFitCheck.pairs(2)).isEqualTo("1 пара");
        assertThat(LoadFitCheck.pairs(3)).isEqualTo("1,5 пари");
        assertThat(LoadFitCheck.pairs(8)).isEqualTo("4 пари");
        assertThat(LoadFitCheck.pairs(40)).isEqualTo("20 пар");
    }

    /** {@code whole} BOTH units and {@code halves} half units of a cohort. */
    private static List<ProblemSnapshot.Unit> units(UUID group, int subgroup, int whole, int halves, UUID teacher,
                                                    RoomType type) {
        List<ProblemSnapshot.Unit> units = new ArrayList<>();
        for (int i = 0; i < whole; i++) {
            units.add(unit(group, subgroup, UnitWeight.WHOLE, teacher, type));
        }
        for (int i = 0; i < halves; i++) {
            units.add(unit(group, subgroup, UnitWeight.HALF, teacher, type));
        }
        return units;
    }

    private static ProblemSnapshot.Unit unit(UUID group, int subgroup, UnitWeight weight, UUID teacher,
                                             RoomType type) {
        return new ProblemSnapshot.Unit(UUID.randomUUID().toString(), null, group, subgroup, DISCIPLINE, teacher,
                weight, type, null, null, null);
    }

    private static ProblemSnapshot snapshot(List<ProblemSnapshot.Unit> units,
                                            List<ProblemSnapshot.Timeslot> unavailable) {
        return snapshot(units, unavailable, 1);
    }

    private static ProblemSnapshot snapshot(List<ProblemSnapshot.Unit> units,
                                            List<ProblemSnapshot.Timeslot> unavailable, int gymPlaces) {
        List<ProblemSnapshot.ShiftWindow> windows = new ArrayList<>();
        for (int weekday = 1; weekday <= 5; weekday++) {
            windows.add(new ProblemSnapshot.ShiftWindow(1, weekday, 1, 4));
            windows.add(weekday == 5 ? new ProblemSnapshot.ShiftWindow(2, weekday, 4, 6)
                    : new ProblemSnapshot.ShiftWindow(2, weekday, 5, 8));
        }
        return new ProblemSnapshot(List.of(), windows,
                List.of(new ProblemSnapshot.Group(TK, "2ТК", 0, 2, List.of()),
                        new ProblemSnapshot.Group(KI, "4КІ1", 0, 1, List.of())),
                List.of(new ProblemSnapshot.Room(GYM, "с.з.", RoomType.GYM, 0, gymPlaces)),
                List.of(new ProblemSnapshot.Teacher(TEACHER, "Кренцін М.Д.", unavailable, List.of(), 4)),
                units);
    }
}
