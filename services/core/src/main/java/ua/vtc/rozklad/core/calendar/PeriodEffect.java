package ua.vtc.rozklad.core.calendar;

/**
 * What a calendar period does with the lessons (DOMAIN §2). The closed list of behaviours: a period kind
 * ({@link PeriodKind}) is data and has exactly one effect, so a new kind needs no code as long as one of
 * these fits. Declared in the order of priority on overlap: DAY_OFF > SUSPEND > ONLINE > INFO
 * (a later BLOCK goes between DAY_OFF and SUSPEND).
 */
public enum PeriodEffect {

    /** A day off for everybody in scope; with scope ALL the date stops being a study day of the college. */
    DAY_OFF,
    /** The groups in scope are not at their lessons (practice, НС, сесія…); teachers work as usual. */
    SUSPEND,
    /** Lessons stay and go online or mixed ({@code studyMode}); the room is freed. */
    ONLINE,
    /** Only a note: lessons are not touched. */
    INFO;

    /** DAY_OFF and SUSPEND remove the lessons of everybody in scope. */
    public boolean closesLessons() {
        return this == DAY_OFF || this == SUSPEND;
    }
}
