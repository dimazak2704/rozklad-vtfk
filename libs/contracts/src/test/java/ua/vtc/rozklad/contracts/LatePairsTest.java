package ua.vtc.rozklad.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LatePairsTest {

    @Test
    void theFourthPairOfTheShiftWindowIsTheFirstLateOne() {
        assertThat(LatePairs.isLate(3, 1)).as("shift 1, pair 3").isFalse();
        assertThat(LatePairs.isLate(4, 1)).as("shift 1, pair 4 (12:10–13:10)").isTrue();
        assertThat(LatePairs.isLate(7, 5)).as("shift 2 Mon–Thu, pair 7").isFalse();
        assertThat(LatePairs.isLate(8, 5)).as("shift 2 Mon–Thu, pair 8").isTrue();
    }

    @Test
    void fridayShiftTwoHasNoLatePair() {
        // Friday shift 2 is pairs 4–6: its last pair is only the 3rd of the window
        assertThat(LatePairs.isLate(6, 4)).isFalse();
    }
}
