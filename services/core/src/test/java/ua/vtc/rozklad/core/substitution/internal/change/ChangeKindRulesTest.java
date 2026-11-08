package ua.vtc.rozklad.core.substitution.internal.change;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.ChangeKind;
import ua.vtc.rozklad.core.shared.domain.ReasonCode;
import ua.vtc.rozklad.core.substitution.internal.change.ChangeKindRules.Violation;

/** Required and forbidden fields of every kind of change. */
class ChangeKindRulesTest {

    private static final UUID ID = UUID.randomUUID();

    @Test
    void replacementOfATeacherNeedsTheLessonAndTheNewTeacher() {
        assertThat(ChangeKindRules.check(request(ChangeKind.REPLACE_TEACHER, 2, null, null, null, null, null, null,
                ReasonCode.ABSENT))).containsExactlyInAnyOrder(
                new Violation("originalLessonId", "Оберіть заняття, яке змінюється"),
                new Violation("newTeacherId", "Вкажіть викладача, який проведе пару"));
        assertThat(ChangeKindRules.check(request(ChangeKind.REPLACE_TEACHER, 2, null, ID, null, ID, null, null,
                ReasonCode.ABSENT))).isEmpty();
    }

    @Test
    void replacementOfADisciplineNeedsTheDiscipline() {
        assertThat(ChangeKindRules.check(request(ChangeKind.REPLACE_DISCIPLINE, 2, null, ID, null, ID, null, null,
                ReasonCode.MUTUAL))).extracting(Violation::field).containsExactly("newDisciplineId");
    }

    @Test
    void cancellationCarriesNothingNew() {
        assertThat(ChangeKindRules.check(request(ChangeKind.CANCEL, 2, null, ID, ID, ID, ID, null, ReasonCode.ABSENT)))
                .extracting(Violation::field).containsExactlyInAnyOrder("newTeacherId", "newDisciplineId", "newRoomId");
    }

    @Test
    void swapNeedsTheMovedLessonAndTakesTheTeacherFromIt() {
        assertThat(ChangeKindRules.check(request(ChangeKind.SWAP, 2, null, ID, null, ID, null, null,
                ReasonCode.ABSENT))).extracting(Violation::field)
                .containsExactlyInAnyOrder("swapLessonId", "newTeacherId");
    }

    @Test
    void roomChangeNeedsANewRoomOnly() {
        assertThat(ChangeKindRules.check(request(ChangeKind.ROOM_CHANGE, 3, null, ID, null, null, null, null,
                ReasonCode.OTHER))).extracting(Violation::field).containsExactly("newRoomId");
    }

    @Test
    void finishedDisciplineIsAlwaysVychytAndTakesBothOrNoReplacement() {
        assertThat(ChangeKindRules.check(request(ChangeKind.DISCIPLINE_FINISHED, 3, null, ID, ID, null, null, null,
                ReasonCode.ABSENT))).extracting(Violation::field)
                .containsExactlyInAnyOrder("reasonCode", "newTeacherId");
        assertThat(ChangeKindRules.check(request(ChangeKind.DISCIPLINE_FINISHED, 3, null, ID, null, null, null, null,
                ReasonCode.FINISHED))).isEmpty();
    }

    @Test
    void onlyBlocksAndExtraPairsSpanSeveralPairs() {
        assertThat(ChangeKindRules.check(request(ChangeKind.CANCEL, 1, 4, ID, null, null, null, null,
                ReasonCode.ABSENT))).containsExactly(
                new Violation("pairTo", "Діапазон пар можливий лише для блоку або додаткових пар"));
        assertThat(ChangeKindRules.check(request(ChangeKind.BLOCK, 1, 4, null, ID, ID, ID, null, ReasonCode.OTHER)))
                .isEmpty();
        assertThat(ChangeKindRules.check(request(ChangeKind.BLOCK, 4, 1, null, ID, null, null, null, ReasonCode.OTHER)))
                .containsExactly(new Violation("pairTo", "Остання пара не може бути раніше за першу"));
    }

    @Test
    void extraPairDoesNotReplaceALesson() {
        assertThat(ChangeKindRules.check(request(ChangeKind.ADDITIONAL, 4, null, ID, null, null, null, null,
                ReasonCode.OTHER))).extracting(Violation::field)
                .containsExactlyInAnyOrder("originalLessonId", "newDisciplineId", "newTeacherId");
    }

    private static CreateChangeRequest request(ChangeKind kind, int pairFrom, Integer pairTo, UUID originalLessonId,
                                               UUID newDisciplineId, UUID newTeacherId, UUID newRoomId,
                                               UUID swapLessonId, ReasonCode reasonCode) {
        return new CreateChangeRequest(kind, LocalDate.of(2026, 10, 19), pairFrom, pairTo, UUID.randomUUID(), null,
                originalLessonId, newDisciplineId, newTeacherId, newRoomId, swapLessonId, reasonCode, null, null);
    }
}
