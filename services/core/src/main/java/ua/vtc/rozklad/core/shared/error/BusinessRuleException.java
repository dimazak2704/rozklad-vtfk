package ua.vtc.rozklad.core.shared.error;

import java.util.Objects;

/**
 * 422 Unprocessable Content: the request is valid but breaks a domain rule. {@code code} is a
 * stable machine-readable identifier (e.g. {@code ORDER_ALREADY_PUBLISHED}) returned as
 * {@code code} in the ProblemDetail; the message is Ukrainian and shown to the user.
 */
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
