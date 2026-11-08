package ua.vtc.rozklad.importer.apply;

/** A core import call failed; {@code status} 0 = no HTTP answer (network, token). */
public class CoreImportException extends RuntimeException {

    private final int status;

    public CoreImportException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
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
