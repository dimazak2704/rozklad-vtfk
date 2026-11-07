package ua.vtc.rozklad.notification.web;

/** 503: a dependency (Telegram bot, LibreOffice, core) is not available; the detail is Ukrainian. */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
