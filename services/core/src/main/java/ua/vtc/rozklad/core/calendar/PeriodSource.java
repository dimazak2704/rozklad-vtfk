package ua.vtc.rozklad.core.calendar;

/**
 * Where a calendar period comes from: entered by the dispatcher, or written by the import of the college's
 * academic calendar (графік освітнього процесу). An import touches only its own rows; a dispatcher's edit of
 * an imported row makes it MANUAL, so the next import leaves it alone.
 */
public enum PeriodSource {
    MANUAL,
    IMPORT
}
