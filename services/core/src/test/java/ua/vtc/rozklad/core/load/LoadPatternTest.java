package ua.vtc.rozklad.core.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** Weekly units of DOMAIN §4: whole pairs plus at most one half (numerator or denominator) pair. */
class LoadPatternTest {

    @ParameterizedTest(name = "{0} год/тиждень по {1} год за пару → {2} + {3}×½")
    @CsvSource({
            "3, 2, 1, 1",      // DOMAIN: 3 год → 1 + ½
            "4, 2, 2, 0",      // 4 год → 2
            "2, 2, 1, 0",      // 2 год → 1
            "1, 2, 0, 1",      // 1 год → ½
            "0.4, 2, 0, 0",    // залишок 0.2 < 0.25 — нічого
            "0.5, 2, 0, 1",    // рівно 0.25 — половинка
            "2.5, 2, 1, 1",    // 1.25 пари
            "1.4, 2, 0, 1",    // 0.7 — ще половинка
            "3.5, 2, 2, 0",    // 1.75 — рівно 0.75, ще одна ціла
            "6, 2, 3, 0",
            "3, 1, 3, 0",      // інша кількість годин за пару
            "1.5, 1, 1, 1"
    })
    void hoursPerWeekBecomeWholeAndHalfUnits(BigDecimal hours, int hoursPerPair, int whole, int half) {
        LoadPattern pattern = LoadPattern.of(hours, hoursPerPair);

        assertThat(pattern).isEqualTo(new LoadPattern(whole, half));
    }

    @Test
    void pairsPerWeekCountHalvesAsHalf() {
        assertThat(LoadPattern.of(new BigDecimal("3"), 2).pairsPerWeek()).isEqualByComparingTo("1.5");
    }

    @Test
    void rejectsImpossibleInput() {
        assertThatThrownBy(() -> LoadPattern.of(BigDecimal.ONE, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoadPattern(1, 2)).isInstanceOf(IllegalArgumentException.class);
    }
}
