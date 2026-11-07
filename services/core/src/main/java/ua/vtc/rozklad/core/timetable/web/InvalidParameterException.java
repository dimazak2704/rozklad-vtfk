package ua.vtc.rozklad.core.timetable.web;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import ua.vtc.rozklad.core.shared.error.FieldErrorDto;
import ua.vtc.rozklad.core.shared.error.ProblemTypes;

/**
 * 400 with the same shape as Bean Validation errors ({@code errors[{field, message}]}) for checks
 * that span several request parameters, e.g. a date range.
 */
class InvalidParameterException extends ErrorResponseException {

    InvalidParameterException(String field, String message) {
        super(HttpStatus.BAD_REQUEST, problem(field, message), null);
    }

    private static ProblemDetail problem(String field, String message) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Перевірте виділені поля.");
        problem.setType(ProblemTypes.VALIDATION);
        problem.setTitle("Некоректні дані");
        problem.setProperty("errors", List.of(new FieldErrorDto(field, message)));
        return problem;
    }
}
