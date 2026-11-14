package ua.vtc.rozklad.notification.core;

/**
 * core or importer answered 4xx. {@code detail} is the Ukrainian ProblemDetail text meant for the
 * user (may be {@code null} when the body was not a ProblemDetail); {@code code} is the ProblemDetail's
 * {@code code} when it has one ({@code HISTORY_NOT_PUBLIC}, the codes of 422).
 */
public class UpstreamRejectedException extends RuntimeException {

    /** core's 404 for a date before the public history window. */
    public static final String HISTORY_NOT_PUBLIC = "HISTORY_NOT_PUBLIC";

    private final int status;
    private final String detail;
    private final String code;

    public UpstreamRejectedException(int status, String detail) {
        this(status, detail, null);
    }

    public UpstreamRejectedException(int status, String detail, String code) {
        super("HTTP " + status + (detail == null ? "" : ": " + detail));
        this.status = status;
        this.detail = detail;
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** core refused a date before the public history window to a caller without the archive. */
    public boolean isHistoryNotPublic() {
        return status == 404 && HISTORY_NOT_PUBLIC.equals(code);
    }

    public int status() {
        return status;
    }

    public String detail() {
        return detail;
    }

    public boolean isNotFound() {
        return status == 404;
    }

    public boolean isConflict() {
        return status == 409;
    }

    public boolean isForbidden() {
        return status == 403;
    }
}
