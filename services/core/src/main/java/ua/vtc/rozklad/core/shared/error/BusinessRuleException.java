package ua.vtc.rozklad.core.shared.error;

import java.util.Map;
import java.util.Objects;

/**
 * 422 Unprocessable Content: the request is valid but breaks a domain rule. {@code code} is a
 * stable machine-readable identifier (e.g. {@code ORDER_ALREADY_PUBLISHED}) returned as
 * {@code code} in the ProblemDetail; the message is Ukrainian and shown to the user.
 */
public class BusinessRuleException extends RuntimeException {

    private final String code;
    private final transient Map<String, Object> properties;

    public BusinessRuleException(String code, String message) {
        this(code, message, Map.of());
    }

    /**
     * @param properties extra ProblemDetail members besides {@code code} (e.g. {@code problems} of the
     *                   generation pre-check); values must serialise to JSON
     */
    public BusinessRuleException(String code, String message, Map<String, Object> properties) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.properties = Map.copyOf(properties);
    }

    public String getCode() {
        return code;
    }

    /** Extra ProblemDetail members, copied into the 422 body. */
    public Map<String, Object> getProperties() {
        return properties;
    }
}
