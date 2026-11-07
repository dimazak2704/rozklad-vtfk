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
}
