package ua.vtc.rozklad.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ParityTest {

    @Test
    void bothOverlapsEveryParity() {
        for (Parity parity : Parity.values()) {
            assertThat(Parity.BOTH.overlaps(parity)).isTrue();
            assertThat(parity.overlaps(Parity.BOTH)).isTrue();
        }
    }

    @Test
    void numeratorAndDenominatorNeverMeet() {
        assertThat(Parity.NUMERATOR.overlaps(Parity.DENOMINATOR)).isFalse();
        assertThat(Parity.DENOMINATOR.overlaps(Parity.NUMERATOR)).isFalse();
        assertThat(Parity.NUMERATOR.overlaps(Parity.NUMERATOR)).isTrue();
    }

    @Test
    void onlyTerminalGenerationStatusesFinishAJob() {
        assertThat(GenerationStatus.values()).filteredOn(GenerationStatus::isTerminal)
                .containsExactly(GenerationStatus.SOLVED, GenerationStatus.FAILED, GenerationStatus.CANCELLED);
    }
}
