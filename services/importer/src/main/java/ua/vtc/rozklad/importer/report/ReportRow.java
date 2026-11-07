package ua.vtc.rozklad.importer.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One line of an import report (CONTRACTS §5 {@code ReportRow}) plus an additive Ukrainian
 * {@code message} that explains the row to the admin.
 *
 * @param raw      the value as found in the file (or the canonical in-file label for entities)
 * @param location where it was found, e.g. «Розклад друк!C10» or «таблиця 2, рядок 4»
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportRow(Severity severity, RowType type, String raw, ScoredRef matched, List<ScoredRef> suggestions,
                        String location, String message) {

    public ReportRow {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    public static ReportRow of(Severity severity, RowType type, String raw, String location, String message) {
        return new ReportRow(severity, type, raw, null, List.of(), location, message);
    }

    /** An entity row that still waits for the admin: no match but similar catalog entries exist. */
    public boolean needsDecision() {
        return type.isEntity() && severity == Severity.WARN && matched == null && !suggestions.isEmpty();
    }

    /** Blocks applying the import: an error or an undecided possible duplicate. */
    public boolean blocking() {
        return severity == Severity.ERROR || needsDecision();
    }
}
