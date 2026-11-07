package ua.vtc.rozklad.notification.core;

/**
 * core or importer answered 4xx. {@code detail} is the Ukrainian ProblemDetail text meant for the
 * user (may be {@code null} when the body was not a ProblemDetail).
 */
public class UpstreamRejectedException extends RuntimeException {

    private final int status;
    private final String detail;

    public UpstreamRejectedException(int status, String detail) {
        super("HTTP " + status + (detail == null ? "" : ": " + detail));
        this.status = status;
        this.detail = detail;
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
