package ua.vtc.rozklad.importer.ai;

/** AI cannot be used now: not configured, network error, rate limit or overload at the provider. */
public class AiUnavailableException extends RuntimeException {

    public AiUnavailableException(String message) {
        super(message);
    }

    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
