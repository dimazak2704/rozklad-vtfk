package ua.vtc.rozklad.core.calendar;

/**
 * HOLIDAY / NO_CLASSES remove lessons of everybody in scope, PRACTICE removes lessons of the
 * groups in scope, STUDY_MODE keeps lessons and marks them online/mixed, EXAM_SESSION is
 * informational.
 */
public enum CalendarPeriodKind {
    HOLIDAY,
    NO_CLASSES,
    PRACTICE,
    STUDY_MODE,
    EXAM_SESSION
}
