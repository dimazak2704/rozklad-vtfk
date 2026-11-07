package ua.vtc.rozklad.core.substitution.internal.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ua.vtc.rozklad.contracts.ChangeStatus.CANCELLED;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;

/** Transitions of DOMAIN §7. */
class ChangeStatusMachineTest {

    @ParameterizedTest
    @CsvSource({
            "DRAFT, PENDING_CONFIRMATION", "DRAFT, CONFIRMED", "DRAFT, CANCELLED",
            "PENDING_CONFIRMATION, CONFIRMED", "PENDING_CONFIRMATION, DECLINED", "PENDING_CONFIRMATION, CANCELLED",
            "CONFIRMED, APPLIED", "CONFIRMED, CANCELLED",
            "DECLINED, PENDING_CONFIRMATION", "DECLINED, CANCELLED"})
    void allowedTransitions(ChangeStatus from, ChangeStatus to) {
        assertThat(ChangeStatusMachine.canTransition(from, to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "DRAFT, APPLIED", "DRAFT, DECLINED",
            "PENDING_CONFIRMATION, APPLIED", "PENDING_CONFIRMATION, PENDING_CONFIRMATION",
            "CONFIRMED, DECLINED", "CONFIRMED, PENDING_CONFIRMATION", "CONFIRMED, CONFIRMED",
            "DECLINED, CONFIRMED", "DECLINED, APPLIED",
            "CANCELLED, CONFIRMED", "CANCELLED, PENDING_CONFIRMATION",
            "APPLIED, CANCELLED", "APPLIED, CONFIRMED"})
    void illegalTransitionsAreBusinessRuleViolations(ChangeStatus from, ChangeStatus to) {
        assertThat(ChangeStatusMachine.canTransition(from, to)).isFalse();
        assertThatThrownBy(() -> ChangeStatusMachine.requireTransition(from, to))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining(ChangeStatusMachine.label(from))
                .hasMessageContaining(ChangeStatusMachine.label(to))
                .extracting(error -> ((BusinessRuleException) error).getCode())
                .isEqualTo(ChangeStatusMachine.ILLEGAL_TRANSITION);
    }

    @ParameterizedTest
    @EnumSource(value = ChangeStatus.class, names = {"APPLIED", "CANCELLED"})
    void appliedAndCancelledAreFinal(ChangeStatus status) {
        assertThat(ChangeStatusMachine.isFinal(status)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ChangeStatus.class, names = {"DRAFT", "PENDING_CONFIRMATION", "CONFIRMED", "DECLINED"})
    void otherStatusesCanStillChange(ChangeStatus status) {
        assertThat(ChangeStatusMachine.isFinal(status)).isFalse();
        assertThat(ChangeStatusMachine.canTransition(status, CANCELLED)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"DRAFT, Чернетка", "PENDING_CONFIRMATION, Очікує підтвердження", "CONFIRMED, Підтверджено",
            "DECLINED, Відхилено", "CANCELLED, Скасовано", "APPLIED, Застосовано"})
    void statusesHaveUkrainianLabels(ChangeStatus status, String label) {
        assertThat(ChangeStatusMachine.label(status)).isEqualTo(label);
    }
}
