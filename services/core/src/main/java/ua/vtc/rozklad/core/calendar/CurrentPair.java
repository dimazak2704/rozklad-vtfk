package ua.vtc.rozklad.core.calendar;

import java.time.LocalTime;

/** The pair going on right now; {@code minutesLeft} is rounded up («до кінця пари 23 хв»). */
public record CurrentPair(int pair, LocalTime startsAt, LocalTime endsAt, int minutesLeft) {
}
