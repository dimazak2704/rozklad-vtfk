package ua.vtc.rozklad.core.timetable;

/** Lifecycle of a timetable version: one PUBLISHED per semester, the previous one gets ARCHIVED. */
public enum VersionStatus {
    DRAFT,
    PUBLISHED,
    ARCHIVED
}
