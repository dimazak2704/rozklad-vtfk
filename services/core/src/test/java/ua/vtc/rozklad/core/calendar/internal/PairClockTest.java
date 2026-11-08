package ua.vtc.rozklad.core.calendar.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import ua.vtc.rozklad.core.calendar.CurrentPair;
import ua.vtc.rozklad.core.calendar.PairTime;

/** «Зараз 3 пара, до кінця 23 хв» with the Friday times of «2 зміни, 60 хв». */
class PairClockTest {

    private static final List<PairTime> FRIDAY = List.of(
            new PairTime(1, LocalTime.of(8, 30), LocalTime.of(9, 30)),
            new PairTime(2, LocalTime.of(9, 40), LocalTime.of(10, 40)),
            new PairTime(3, LocalTime.of(11, 0), LocalTime.of(12, 0)),
            new PairTime(4, LocalTime.of(12, 20), LocalTime.of(13, 20)));

    @Test
    void currentPairWithMinutesLeftRoundedUp() {
        CurrentPair pair = PairClock.current(FRIDAY, LocalTime.of(11, 37, 30)).orElseThrow();

        assertThat(pair.pair()).isEqualTo(3);
        assertThat(pair.minutesLeft()).isEqualTo(23);
        assertThat(PairClock.next(FRIDAY, LocalTime.of(11, 37, 30)))
                .contains(new PairTime(4, LocalTime.of(12, 20), LocalTime.of(13, 20)));
    }

    @Test
    void pairStartsInclusiveAndEndsExclusive() {
        assertThat(PairClock.current(FRIDAY, LocalTime.of(8, 30))).map(CurrentPair::minutesLeft).contains(60);
        assertThat(PairClock.current(FRIDAY, LocalTime.of(9, 30))).isEmpty();
        assertThat(PairClock.current(FRIDAY, LocalTime.of(9, 29, 59, 500_000_000))).map(CurrentPair::minutesLeft)
                .contains(1);
    }

    @Test
    void breakHasNoCurrentPairButANextOne() {
        assertThat(PairClock.current(FRIDAY, LocalTime.of(12, 10))).isEmpty();
        assertThat(PairClock.next(FRIDAY, LocalTime.of(12, 10))).map(PairTime::pair).contains(4);
    }

    @Test
    void beforeFirstAndAfterLastPair() {
        assertThat(PairClock.next(FRIDAY, LocalTime.of(7, 0))).map(PairTime::pair).contains(1);
        assertThat(PairClock.current(FRIDAY, LocalTime.of(14, 0))).isEmpty();
        assertThat(PairClock.next(FRIDAY, LocalTime.of(14, 0))).isEmpty();
    }
}
