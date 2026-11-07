package ua.vtc.rozklad.importer.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Mutable collector of report rows produced while parsing one file. */
public final class Issues {

    private final List<ReportRow> rows = new ArrayList<>();

    public void info(RowType type, String raw, String location, String message) {
        add(Severity.INFO, type, raw, location, message);
    }

    public void warn(RowType type, String raw, String location, String message) {
        add(Severity.WARN, type, raw, location, message);
    }

    public void error(RowType type, String raw, String location, String message) {
        add(Severity.ERROR, type, raw, location, message);
    }

    public void add(Severity severity, RowType type, String raw, String location, String message) {
        rows.add(ReportRow.of(severity, type, raw, location, message));
    }

    public void add(ReportRow row) {
        rows.add(row);
    }

    public List<ReportRow> rows() {
        return Collections.unmodifiableList(rows);
    }

    public boolean hasErrors() {
        return rows.stream().anyMatch(row -> row.severity() == Severity.ERROR);
    }
}
