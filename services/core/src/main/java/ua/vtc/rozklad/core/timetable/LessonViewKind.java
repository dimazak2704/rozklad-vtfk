package ua.vtc.rozklad.core.timetable;

/**
 * How a dated lesson is shown in a schedule (CONTRACTS.md §1). REGULAR is the base timetable; the
 * other kinds come from applied changes (заміни, перенесення, додаткові пари, блоки).
 */
public enum LessonViewKind {
    REGULAR,
    SUBSTITUTED,
    CANCELLED,
    ADDITIONAL,
    MOVED,
    ROOM_CHANGED,
    BLOCK
}
