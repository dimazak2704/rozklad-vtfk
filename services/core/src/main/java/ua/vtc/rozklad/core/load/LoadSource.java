package ua.vtc.rozklad.core.load;

/** Where a load item came from; TIMETABLE_DERIVED items are computed from a published version. */
public enum LoadSource {
    MANUAL,
    CURRICULUM,
    PROGRAM,
    TIMETABLE_DERIVED
}
