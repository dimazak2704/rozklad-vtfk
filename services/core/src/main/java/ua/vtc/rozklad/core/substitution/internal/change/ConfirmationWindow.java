package ua.vtc.rozklad.core.substitution.internal.change;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.calendar.PairTime;
import ua.vtc.rozklad.core.shared.time.TimeConfig;
import ua.vtc.rozklad.core.substitution.Change;
import ua.vtc.rozklad.core.substitution.internal.support.SubstitutionCalendar;

/**
 * When asking the assigned teacher still makes sense (DOMAIN §7, CONFIRM): the answer is due before the
 * change's first pair starts, and nobody is asked or reminded about pairs that are already over. Pair
 * times come from the bell profile of the date; without them only the date decides.
 */
@Component
class ConfirmationWindow {

    private final SubstitutionCalendar calendar;

    ConfirmationWindow(SubstitutionCalendar calendar) {
        this.calendar = calendar;
    }

    /** The change's pairs are over at {@code now}: an earlier day, or today after the end of its last pair. */
    boolean lessonOver(Change change, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, TimeConfig.KYIV);
        if (!change.getDate().equals(today)) {
            return change.getDate().isBefore(today);
        }
        PairTime last = calendar.pairTimes(change.getDate()).get(change.getPairTo());
        return last != null && !at(change.getDate(), last.endsAt()).isAfter(now);
    }

    /**
     * The confirmation deadline of a round that starts at {@code now}: {@code now + timeout}, but no later than
     * the start of the change's first pair. When that pair is already going on, the answer is still useful until
     * the last pair ends, so the deadline is capped by that end instead.
     */
    Instant deadline(Change change, Instant now, Duration timeout) {
        Instant byTimeout = now.plus(timeout);
        Map<Integer, PairTime> times = calendar.pairTimes(change.getDate());
        PairTime first = times.get(change.getPairFrom());
        if (first != null) {
            Instant start = at(change.getDate(), first.startsAt());
            if (start.isAfter(now)) {
                return start.isBefore(byTimeout) ? start : byTimeout;
            }
        }
        PairTime last = times.get(change.getPairTo());
        if (last != null) {
            Instant end = at(change.getDate(), last.endsAt());
            if (end.isAfter(now) && end.isBefore(byTimeout)) {
                return end;
            }
        }
        return byTimeout;
    }

    private static Instant at(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(TimeConfig.KYIV).toInstant();
    }
}
