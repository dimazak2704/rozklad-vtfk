package ua.vtc.rozklad.core.calendar.internal;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import ua.vtc.rozklad.core.calendar.CurrentPair;
import ua.vtc.rozklad.core.calendar.PairTime;

/** «Зараз 3 пара, до кінця 23 хв» / «наступна 4 пара о 12:10» from the pair times of one day. */
final class PairClock {

    private static final long MILLIS_PER_MINUTE = 60_000;

    private PairClock() {
    }

    static Optional<CurrentPair> current(List<PairTime> slots, LocalTime time) {
        return slots.stream()
                .filter(slot -> slot.contains(time))
                .findFirst()
                .map(slot -> new CurrentPair(slot.pair(), slot.startsAt(), slot.endsAt(), minutesLeft(time, slot.endsAt())));
    }

    static Optional<PairTime> next(List<PairTime> slots, LocalTime time) {
        return slots.stream()
                .filter(slot -> slot.startsAt().isAfter(time))
                .min(Comparator.comparing(PairTime::startsAt));
    }

    /** Rounded up, so the countdown shows «1 хв» until the very end of the pair. */
    static int minutesLeft(LocalTime now, LocalTime end) {
        long millis = Duration.between(now, end).toMillis();
        return (int) ((millis + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE);
    }
}
