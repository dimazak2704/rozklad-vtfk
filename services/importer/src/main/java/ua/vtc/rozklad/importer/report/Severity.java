package ua.vtc.rozklad.importer.report;

/** Report row severity (CONTRACTS §5). */
public enum Severity {
    /** Recognized and matched. */
    OK,
    /** Informational: e.g. a new entity that will be created, a formatting fix. */
    INFO,
    /** Needs attention: a typo merged, a possible duplicate, dirty data. */
    WARN,
    /** Cannot be imported as is. */
    ERROR
}
