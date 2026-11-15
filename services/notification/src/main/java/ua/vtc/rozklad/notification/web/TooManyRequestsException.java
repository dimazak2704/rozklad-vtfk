package ua.vtc.rozklad.notification.web;

import java.util.Objects;

/** 429: the caller asked too often (test letters); {@code code} is machine-readable, the message Ukrainian. */
public class TooManyRequestsException extends RuntimeException {

    private final String code;

    public TooManyRequestsException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String getCode() {
        return code;
    }
}
