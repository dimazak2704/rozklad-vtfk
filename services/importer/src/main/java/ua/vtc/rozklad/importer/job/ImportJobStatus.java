package ua.vtc.rozklad.importer.job;

/**
 * Job lifecycle (CONTRACTS §5): UPLOADED → PARSING → NEEDS_REVIEW | READY (mappings move between
 * them) → APPLYING → APPLIED; any step may end in FAILED. A job that failed while applying keeps
 * its draft and may be applied again.
 */
public enum ImportJobStatus {
    UPLOADED, PARSING, NEEDS_REVIEW, READY, APPLYING, APPLIED, FAILED
}
