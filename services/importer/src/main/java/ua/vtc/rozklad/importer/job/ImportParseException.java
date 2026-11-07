package ua.vtc.rozklad.importer.job;

/**
 * The uploaded file cannot be parsed; the message is Ukrainian and is shown to the admin as the
 * job error.
 */
public class ImportParseException extends RuntimeException {

    public ImportParseException(String message) {
        super(message);
    }

    public ImportParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
