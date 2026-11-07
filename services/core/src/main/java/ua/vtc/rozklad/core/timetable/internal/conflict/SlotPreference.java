package ua.vtc.rozklad.core.timetable.internal.conflict;

/** Teacher preference for a weekday × pair: UNAVAILABLE is a hard constraint, UNWANTED a soft one. */
public enum SlotPreference {
    UNWANTED,
    UNAVAILABLE
}
