package ua.vtc.rozklad.core.timetable.internal.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.core.load.LoadPattern;
import ua.vtc.rozklad.core.timetable.internal.support.LoadReadRepository.LoadRow;

/** DOMAIN.md §4: load → weekly units. */
class LoadUnitExpanderTest {

    private static final BigDecimal TWO_HOURS = BigDecimal.valueOf(2);
    private static final UUID LOAD_ITEM = UUID.fromString("0190f0a0-0000-7000-8000-0000000000aa");
    private static final UUID GROUP = UUID.randomUUID();
    private static final UUID DISCIPLINE = UUID.randomUUID();
    private static final UUID TEACHER = UUID.randomUUID();
    private static final UUID SECOND_TEACHER = UUID.randomUUID();

    @ParameterizedTest(name = "{0} h/week → {1} whole + {2} half")
    @CsvSource({"3, 1, 1", "4, 2, 0", "2, 1, 0", "1, 0, 1", "3.5, 2, 0", "2.5, 1, 1", "1.4, 0, 1", "0.4, 0, 0"})
    void hoursPerWeekBecomeWholeAndHalfPairs(BigDecimal hours, int whole, int half) {
        assertThat(LoadPattern.of(hours, 2)).isEqualTo(new LoadPattern(whole, half));
    }

    @Test
    void hoursPerPairComesFromTheSetting() {
        assertThat(LoadPattern.of(BigDecimal.valueOf(3), 1)).isEqualTo(new LoadPattern(3, 0));
    }

    @Test
    void unsplitItemGivesWholeGroupUnitsWithItsTeacher() {
        List<PlanUnit> units = LoadUnitExpander.expand(item(BigDecimal.valueOf(3), false, Map.of(0, TEACHER)), Set.of(),
                2);

        assertThat(units).extracting(PlanUnit::unitId, PlanUnit::subgroup, PlanUnit::weight, PlanUnit::teacherId)
                .containsExactly(
                        tuple(LOAD_ITEM + ":0:w1", 0, UnitWeight.WHOLE, TEACHER),
                        tuple(LOAD_ITEM + ":0:h", 0, UnitWeight.HALF, TEACHER));
        assertThat(units).allSatisfy(unit -> {
            assertThat(unit.loadItemId()).isEqualTo(LOAD_ITEM);
            assertThat(unit.requiredRoomType()).isEqualTo(RoomType.COMPUTER_LAB);
            assertThat(unit.lessonKind()).isEqualTo(LessonKind.LAB);
            assertThat(unit.pinned()).isNull();
        });
    }

    @Test
    void splitItemGivesUnitsPerSubgroupWithTheirTeachers() {
        List<PlanUnit> units = LoadUnitExpander.expand(
                item(TWO_HOURS, true, Map.of(1, TEACHER, 2, SECOND_TEACHER)), Set.of(), 2);

        assertThat(units).extracting(PlanUnit::subgroup, PlanUnit::teacherId)
                .containsExactly(tuple(1, TEACHER), tuple(2, SECOND_TEACHER));
    }

    @Test
    void splitItemWithOneTeacherUsesTheSubgroupsOfTheCatalog() {
        List<PlanUnit> units = LoadUnitExpander.expand(item(TWO_HOURS, true, Map.of(0, TEACHER)), Set.of(1, 2),
                2);

        assertThat(units).extracting(PlanUnit::subgroup, PlanUnit::teacherId)
                .containsExactly(tuple(1, TEACHER), tuple(2, TEACHER));
    }

    @Test
    void splitItemWithoutSubgroupsIsRejected() {
        assertThatThrownBy(() -> LoadUnitExpander.expand(item(TWO_HOURS, true, Map.of(0, TEACHER)), Set.of(),
                2)).isInstanceOf(LoadUnitExpander.SplitWithoutSubgroupsException.class);
    }

    @Test
    void itemWithoutTeacherIsAVacancyNotAnError() {
        assertThat(LoadUnitExpander.expand(item(TWO_HOURS, false, Map.of()), Set.of(), 2))
                .singleElement().satisfies(unit -> assertThat(unit.teacherId()).isNull());
    }

    private static LoadRow item(BigDecimal hoursPerWeek, boolean split, Map<Integer, UUID> teachers) {
        return new LoadRow(LOAD_ITEM, GROUP, DISCIPLINE, LessonKind.LAB, hoursPerWeek, split, RoomType.COMPUTER_LAB,
                null, null, teachers);
    }
}
