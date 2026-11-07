package ua.vtc.rozklad.importer.web;

import java.util.Objects;

/** 422: valid request that breaks a rule; {@code code} is machine-readable, the message Ukrainian. */
public class BusinessRuleException extends RuntimeException {

    private final String code;

    public BusinessRuleException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String getCode() {
        return code;
    }
}
