package ua.vtc.rozklad.core.calendar;

import java.time.LocalTime;

/** Time of a pair on a concrete date (from the bell profile in effect). */
public record PairTime(int pair, LocalTime startsAt, LocalTime endsAt) {

    public boolean contains(LocalTime time) {
        return !time.isBefore(startsAt) && time.isBefore(endsAt);
    }
}
