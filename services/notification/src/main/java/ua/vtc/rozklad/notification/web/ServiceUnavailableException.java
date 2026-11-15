package ua.vtc.rozklad.notification.web;

/**
 * 503: a dependency (Telegram bot, LibreOffice, core, the e-mail channel) is not available; the detail is
 * Ukrainian, {@code code} an optional machine-readable reason ({@code email-disabled}).
 */
public class ServiceUnavailableException extends RuntimeException {

    private final String code;

    public ServiceUnavailableException(String message) {
        this(null, message);
    }

    public ServiceUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
