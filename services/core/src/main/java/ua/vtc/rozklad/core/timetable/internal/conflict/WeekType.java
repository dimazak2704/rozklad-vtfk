package ua.vtc.rozklad.core.timetable.internal.conflict;

import ua.vtc.rozklad.contracts.Parity;

/**
 * Kind of week in which lessons meet: numerator or denominator week of the template, or the single
 * week of a concrete date. A BOTH lesson takes place in every week.
 */
public enum WeekType {
    NUMERATOR,
    DENOMINATOR,
    SINGLE;

    public boolean includes(Parity parity) {
        return switch (this) {
            case SINGLE -> true;
            case NUMERATOR -> parity != Parity.DENOMINATOR;
            case DENOMINATOR -> parity != Parity.NUMERATOR;
        };
    }
}
