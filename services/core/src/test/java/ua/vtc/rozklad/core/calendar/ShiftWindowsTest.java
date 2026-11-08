package ua.vtc.rozklad.core.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShiftWindowsTest {

    @Test
    void emptyTableMeansTheWindowsOfTheDomain() {
        ShiftWindows windows = ShiftWindows.of(List.of());

        assertThat(windows.fromDomainDefaults()).isTrue();
        assertThat(windows.rows()).hasSize(10);
        assertThat(windows.window(1, 3)).contains(new ShiftWindows.PairRange(1, 4));
        assertThat(windows.window(2, 4)).contains(new ShiftWindows.PairRange(5, 8));
        assertThat(windows.window(2, 5)).contains(new ShiftWindows.PairRange(4, 6));
    }

    @Test
    void configuredWindowsWinAndAMissingWeekdayIsNotRestricted() {
        ShiftWindows windows = ShiftWindows.of(List.of(new ShiftWindow(1, 1, 1, 5), new ShiftWindow(2, 1, 4, 8)));

        assertThat(windows.fromDomainDefaults()).isFalse();
        assertThat(windows.window(1, 1)).contains(new ShiftWindows.PairRange(1, 5));
        assertThat(windows.window(1, 2)).isEmpty();
        assertThat(windows.rows()).extracting(ShiftWindows.Row::shift).containsExactly(1, 2);
    }
}
