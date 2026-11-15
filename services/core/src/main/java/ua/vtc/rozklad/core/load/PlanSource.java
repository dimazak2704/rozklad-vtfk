package ua.vtc.rozklad.core.load;

/**
 * Where the planned hours of a load item come from (owner's rule 15.11.2026). AUTO: the system counts them from the
 * hours per week and the days the group is on theory, and recounts them when the calendar or the published timetable
 * changes. FIXED: a number the dispatcher or an import (a discipline program) set; the system never changes it.
 */
public enum PlanSource {
    AUTO,
    FIXED
}
