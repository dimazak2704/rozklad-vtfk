package ua.vtc.rozklad.notification.core;

/** core or importer could not be reached or answered 5xx; the caller may retry later. */
public class UpstreamUnavailableException extends RuntimeException {

    private final String service;

    public UpstreamUnavailableException(String service, String message, Throwable cause) {
        super(message, cause);
        this.service = service;
    }

    public String service() {
        return service;
    }
}
