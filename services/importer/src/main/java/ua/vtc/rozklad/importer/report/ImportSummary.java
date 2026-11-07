package ua.vtc.rozklad.importer.report;

import java.util.List;

/**
 * Report totals (CONTRACTS §5 {@code summary}): {@code recognized} — entities matched to the
 * catalog; {@code unknown} — entities not in the catalog (they will be created or need a
 * decision); {@code conflicts} — rows that block applying (errors, undecided possible duplicates);
 * {@code warnings} — other warnings.
 */
public record ImportSummary(int recognized, int unknown, int conflicts, int warnings) {

    /**
     * @param matchingRows rows of catalog matching (one per draft entity)
     * @param allRows      the whole report (matching rows and parse remarks)
     */
    public static ImportSummary of(List<ReportRow> matchingRows, List<ReportRow> allRows) {
        int recognized = 0;
        int unknown = 0;
        for (ReportRow row : matchingRows) {
            if (!row.type().isEntity()) {
                continue;
            }
            if (row.matched() != null) {
                recognized++;
            } else {
                unknown++;
            }
        }
        int conflicts = 0;
        int warnings = 0;
        for (ReportRow row : allRows) {
            if (row.blocking()) {
                conflicts++;
            } else if (row.severity() == Severity.WARN) {
                warnings++;
            }
        }
        return new ImportSummary(recognized, unknown, conflicts, warnings);
    }

    public boolean blocking() {
        return conflicts > 0;
    }
}
