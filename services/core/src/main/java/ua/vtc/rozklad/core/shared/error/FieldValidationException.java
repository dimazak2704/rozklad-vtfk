package ua.vtc.rozklad.core.shared.error;

import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 400 for rules that annotations on a request DTO cannot express: a referenced id does not exist,
 * a natural key repeats inside one request, a value contradicts another field. The body has the
 * same shape as Bean Validation errors ({@code errors[{field, message}]}), so forms highlight the
 * field. Collect several errors with {@link FieldErrors}.
 */
public class FieldValidationException extends ErrorResponseException {

    private final transient List<FieldErrorDto> errors;

    public FieldValidationException(List<FieldErrorDto> errors) {
        super(HttpStatus.BAD_REQUEST, problem(errors), null);
        this.errors = sorted(errors);
    }

    public static FieldValidationException of(String field, String message) {
        return new FieldValidationException(List.of(new FieldErrorDto(field, message)));
    }

    public List<FieldErrorDto> getErrors() {
        return errors;
    }

    @Override
    public String getMessage() {
        return "Validation failed: " + errors;
    }

    private static ProblemDetail problem(List<FieldErrorDto> errors) {
        if (errors == null || errors.isEmpty()) {
            throw new IllegalArgumentException("at least one field error is required");
        }
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Перевірте виділені поля.");
        body.setType(ProblemTypes.VALIDATION);
        body.setTitle("Некоректні дані");
        body.setProperty("errors", sorted(errors));
        return body;
    }

    private static List<FieldErrorDto> sorted(List<FieldErrorDto> errors) {
        return errors.stream()
                .sorted(Comparator.comparing(FieldErrorDto::field, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }
}
