package ua.vtc.rozklad.core.timetable;

/** How a dated lesson appeared: materialized from the template or created by an applied change. */
public enum LessonOrigin {
    TEMPLATE,
    CHANGE,
    ADDITIONAL,
    BLOCK
}
