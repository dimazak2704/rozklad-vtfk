package ua.vtc.rozklad.notification.web;

/** 502: an upstream (Telegram, core) rejected our call; the detail is Ukrainian. */
public class BadGatewayException extends RuntimeException {

    public BadGatewayException(String message) {
        super(message);
    }
}
