package ua.vtc.rozklad.core.substitution.internal.options;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.vtc.rozklad.core.substitution.internal.options.RankingInputs.DATE;
import static ua.vtc.rozklad.core.substitution.internal.options.RankingInputs.id;
import static ua.vtc.rozklad.core.substitution.internal.options.RankingInputs.lesson;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Day plan of an absence (DOMAIN §7): one proposal per lesson, nothing sent. */
class DayPlannerTest {

    private static final UUID GROUP = id("4КІ1");

    /** A lesson of the absent teacher at {@code pair} whose other lesson that day is at {@code other}. */
    private static RankingInputs absentLesson(int pair, int other) {
        return lesson(pair).lessonId(id("СТП" + pair)).absentTeachersLesson(other, false)
                .discipline("НДКС", "Непийвода").discipline("ОПЦЗ", "Ганза");
    }

    private static List<DayPlanner.Planned> plan(RankingInputs... inputs) {
        List<RankingInput> built = java.util.Arrays.stream(inputs).map(RankingInputs::build).toList();
        Map<UUID, UUID> groups = new java.util.HashMap<>();
        built.forEach(input -> groups.put(input.target().lessonId(), GROUP));
        return DayPlanner.plan(built, groups);
    }

    private static UUID proposedTeacher(DayPlanner.Planned planned) {
        return planned.options().get(planned.proposedIndex()).teacherId();
    }

    @Test
    void aTeacherProposedForOnePairCostsMoreForAnotherPairOfTheAbsence() {
        List<DayPlanner.Planned> planned = plan(absentLesson(2, 5), absentLesson(5, 2));

        assertThat(planned).extracting(item -> item.input().target().pair()).containsExactly(2, 5);
        UUID first = proposedTeacher(planned.get(0));
        assertThat(proposedTeacher(planned.get(1))).isNotEqualTo(first);
        assertThat(planned.get(1).options()).filteredOn(option -> first.equals(option.teacherId()))
                .singleElement().satisfies(option -> assertThat(option.reasons())
                        .contains("У плані дня вже має іншу пару"));
    }

    @Test
    void theNextPairOfTheSameCohortStaysWithTheSameTeacher() {
        List<DayPlanner.Planned> planned = plan(absentLesson(2, 3), absentLesson(3, 2));

        assertThat(proposedTeacher(planned.get(1))).isEqualTo(proposedTeacher(planned.get(0)));
        assertThat(planned.get(1).options().getFirst().reasons()).contains("У плані дня веде й сусідню пару");
    }

    @Test
    void lessonsWithFewerCleanOptionsArePlannedFirst() {
        RankingInputs crowded = absentLesson(2, 5);
        RankingInputs scarce = lesson(5).lessonId(id("СТП5")).absentTeachersLesson(2, false)
                .discipline("ОПЦЗ", "Ганза");

        List<DayPlanner.Planned> planned = plan(crowded, scarce);

        assertThat(planned).extracting(item -> item.input().target().pair()).containsExactly(5, 2);
        assertThat(proposedTeacher(planned.get(0))).isEqualTo(id("Ганза"));
        assertThat(proposedTeacher(planned.get(1))).isEqualTo(id("Непийвода"));
    }

    @Test
    void aPastLessonGetsNoProposal() {
        List<DayPlanner.Planned> planned = plan(absentLesson(2, 5).today(DATE.plusDays(1)));

        assertThat(planned).singleElement().satisfies(item -> {
            assertThat(item.proposedIndex()).isNull();
            assertThat(item.options()).isNotEmpty();
        });
    }

    @Test
    void thePlanIsDeterministic() {
        assertThat(plan(absentLesson(5, 2), absentLesson(2, 5))).isEqualTo(plan(absentLesson(2, 5),
                absentLesson(5, 2)));
    }
}
