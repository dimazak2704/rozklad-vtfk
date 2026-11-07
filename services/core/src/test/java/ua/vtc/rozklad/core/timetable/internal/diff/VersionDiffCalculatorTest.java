package ua.vtc.rozklad.core.timetable.internal.diff;

import static org.assertj.core.api.Assertions.assertThat;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.D_FV;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.D_KM;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.D_NDEKSM;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.D_STP;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.G_4KI1;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.G_4KI2;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.R_304;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.R_LAB6;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.T_KRENTSIN;
import static ua.vtc.rozklad.core.timetable.internal.conflict.ConflictFixtures.T_NEPYIVODA;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.timetable.TemplateLesson;

class VersionDiffCalculatorTest {

    private static final UUID VERSION_A = UUID.randomUUID();
    private static final UUID VERSION_B = UUID.randomUUID();

    private final VersionDiffCalculator calculator = new VersionDiffCalculator();

    @Test
    void reportsAddedRemovedAndMovedLessons() {
        TemplateLesson unchangedA = lesson(VERSION_A, 1, 1, G_4KI1, D_STP, T_KRENTSIN, R_304);
        TemplateLesson movedA = lesson(VERSION_A, 2, 2, G_4KI1, D_KM, T_NEPYIVODA, R_LAB6);
        TemplateLesson removedA = lesson(VERSION_A, 3, 3, G_4KI2, D_FV, null, null);
        TemplateLesson unchangedB = lesson(VERSION_B, 1, 1, G_4KI1, D_STP, T_KRENTSIN, R_304);
        TemplateLesson movedB = lesson(VERSION_B, 4, 3, G_4KI1, D_KM, T_NEPYIVODA, R_LAB6);
        TemplateLesson addedB = lesson(VERSION_B, 5, 1, G_4KI2, D_NDEKSM, T_KRENTSIN, null);

        VersionDiffCalculator.Diff diff = calculator.diff(List.of(unchangedA, movedA, removedA),
                List.of(unchangedB, movedB, addedB));

        assertThat(diff.added()).containsExactly(addedB);
        assertThat(diff.removed()).containsExactly(removedA);
        assertThat(diff.moved()).containsExactly(new VersionDiffCalculator.Moved(movedA, movedB));
    }

    @Test
    void roomChangeAndParityChangeAreMoves() {
        TemplateLesson inRoom = lesson(VERSION_A, 1, 2, G_4KI1, D_STP, T_KRENTSIN, R_304);
        TemplateLesson inLab = lesson(VERSION_B, 1, 2, G_4KI1, D_STP, T_KRENTSIN, R_LAB6);
        TemplateLesson half = lesson(VERSION_A, 3, 3, G_4KI1, D_KM, null, null);
        half.moveTo(3, 3, Parity.NUMERATOR);
        TemplateLesson otherHalf = lesson(VERSION_B, 3, 3, G_4KI1, D_KM, null, null);
        otherHalf.moveTo(3, 3, Parity.DENOMINATOR);

        VersionDiffCalculator.Diff diff = calculator.diff(List.of(inRoom, half), List.of(inLab, otherHalf));

        assertThat(diff.added()).isEmpty();
        assertThat(diff.removed()).isEmpty();
        assertThat(diff.moved()).containsExactlyInAnyOrder(new VersionDiffCalculator.Moved(inRoom, inLab),
                new VersionDiffCalculator.Moved(half, otherHalf));
    }

    @Test
    void teacherReplacementIsARemovalAndAnAddition() {
        TemplateLesson before = lesson(VERSION_A, 1, 2, G_4KI1, D_STP, T_KRENTSIN, R_304);
        TemplateLesson after = lesson(VERSION_B, 1, 2, G_4KI1, D_STP, T_NEPYIVODA, R_304);

        VersionDiffCalculator.Diff diff = calculator.diff(List.of(before), List.of(after));

        assertThat(diff.removed()).containsExactly(before);
        assertThat(diff.added()).containsExactly(after);
        assertThat(diff.moved()).isEmpty();
    }

    @Test
    void duplicatesAreCountedAndTheNearestSlotIsMatched() {
        TemplateLesson first = lesson(VERSION_A, 2, 1, G_4KI1, D_STP, T_KRENTSIN, null);
        TemplateLesson twin = lesson(VERSION_A, 2, 1, G_4KI1, D_STP, T_KRENTSIN, null);
        TemplateLesson kept = lesson(VERSION_B, 2, 1, G_4KI1, D_STP, T_KRENTSIN, null);
        TemplateLesson nearby = lesson(VERSION_B, 2, 2, G_4KI1, D_STP, T_KRENTSIN, null);
        TemplateLesson farAway = lesson(VERSION_B, 5, 4, G_4KI1, D_STP, T_KRENTSIN, null);

        VersionDiffCalculator.Diff diff = calculator.diff(List.of(first, twin), List.of(kept, nearby, farAway));

        assertThat(diff.moved()).singleElement().satisfies(moved -> assertThat(moved.to()).isEqualTo(nearby));
        assertThat(diff.added()).containsExactly(farAway);
        assertThat(diff.removed()).isEmpty();
    }

    @Test
    void identicalVersionsHaveNoDifference() {
        TemplateLesson a = lesson(VERSION_A, 1, 1, G_4KI1, D_STP, T_KRENTSIN, R_304);
        TemplateLesson b = lesson(VERSION_B, 1, 1, G_4KI1, D_STP, T_KRENTSIN, R_304);

        VersionDiffCalculator.Diff diff = calculator.diff(List.of(a), List.of(b));

        assertThat(diff.added()).isEmpty();
        assertThat(diff.removed()).isEmpty();
        assertThat(diff.moved()).isEmpty();
    }

    private static TemplateLesson lesson(UUID versionId, int weekday, int pair, UUID groupId, UUID disciplineId,
                                         UUID teacherId, UUID roomId) {
        TemplateLesson lesson = new TemplateLesson(versionId, weekday, pair, Parity.BOTH, groupId, 0, disciplineId);
        lesson.setTeacherId(teacherId);
        lesson.setRoomId(roomId);
        return lesson;
    }
}
