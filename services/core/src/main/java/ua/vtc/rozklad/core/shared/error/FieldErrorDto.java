package ua.vtc.rozklad.core.shared.error;

/** Element of {@code errors[]} in a validation ProblemDetail; {@code message} is Ukrainian. */
public record FieldErrorDto(String field, String message) {
}
