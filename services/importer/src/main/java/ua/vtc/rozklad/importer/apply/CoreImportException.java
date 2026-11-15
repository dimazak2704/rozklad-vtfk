package ua.vtc.rozklad.importer.apply;

import tools.jackson.databind.JsonNode;

/** A core import call failed; {@code status} 0 = no HTTP answer (network, token). */
public class CoreImportException extends RuntimeException {

    private final int status;
    private final transient JsonNode problem;

    public CoreImportException(int status, String message, Throwable cause) {
        this(status, message, null, cause);
    }

    /** @param problem core's ProblemDetail body when it sent one (e.g. {@code conflicts[]} of a 409) */
    public CoreImportException(int status, String message, JsonNode problem, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.problem = problem;
    }

    public int status() {
        return status;
    }

    /** Core's ProblemDetail body, or {@code null}. */
    public JsonNode problem() {
        return problem;
    }

    /**
     * Core understood the request and rejected its content (a taken number, a date outside the
     * semester, an invalid field): the admin can correct the draft or the mappings and apply again.
     * Outages (5xx, no answer) and auth problems are not fixed by a review.
     */
    public boolean fixableByReview() {
        return status == 400 || status == 409 || status == 422;
    }
}
