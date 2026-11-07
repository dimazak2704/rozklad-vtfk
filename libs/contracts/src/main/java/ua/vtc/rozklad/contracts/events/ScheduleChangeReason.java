package ua.vtc.rozklad.contracts.events;

/** Why published lessons changed; consumers use it for cache invalidation and logging only. */
public enum ScheduleChangeReason {
    VERSION_PUBLISHED,
    ORDER_PUBLISHED,
    CALENDAR_CHANGED,
    LESSONS_EDITED
}
