package ua.vtc.rozklad.core.order.internal.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.contracts.Parity;

/** Header of the real orders №35 (19.10.2026) and №96 (22.05.2026). */
class OrderTextsTest {

    @Test
    void headerOfOrder35() {
        LocalDate date = LocalDate.of(2026, 10, 19);

        assertThat(OrderTexts.mainTitle(35)).isEqualTo("РОЗПОРЯДЖЕННЯ №35");
        assertThat(OrderTexts.SUBTITLE).isEqualTo("По коледжу про деякі зміни в розкладі занять");
        assertThat(OrderTexts.dateLine(date)).isEqualTo("На 19 жовтня 2026 р.");
        assertThat(OrderTexts.dayLine(OrderTexts.weekdayLabel(date), OrderTexts.parityLabel(Parity.DENOMINATOR)))
                .isEqualTo("ПОНЕДІЛОК - ЗНАМЕННИК");
    }

    @Test
    void headerOfOrder96OnAFriday() {
        LocalDate date = LocalDate.of(2026, 5, 22);

        assertThat(OrderTexts.dateLine(date)).isEqualTo("На 22 травня 2026 р.");
        assertThat(OrderTexts.weekdayLabel(date)).isEqualTo("П'ЯТНИЦЯ");
    }

    @Test
    void draftHasNoNumberAndAmendmentNamesItsParent() {
        assertThat(OrderTexts.mainTitle(null)).isEqualTo("РОЗПОРЯДЖЕННЯ");
        assertThat(OrderTexts.amendmentTitle(35)).isEqualTo("ДОПОВНЕННЯ до розпорядження №35");
    }

    @Test
    void pairLabels() {
        assertThat(OrderTexts.pairLabel(2, 2)).isEqualTo("2");
        assertThat(OrderTexts.pairLabel(1, 4)).isEqualTo("1-4");
    }
}
