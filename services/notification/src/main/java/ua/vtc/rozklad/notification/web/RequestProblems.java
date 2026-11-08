package ua.vtc.rozklad.notification.web;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Field errors for requests Spring could not bind: a JSON value of the wrong type or format
 * (unknown enum constant, impossible date, a string instead of a list), a query parameter that
 * does not convert. The field is the JSON path ({@code grid[2].pair}) or the parameter name, the
 * message says in Ukrainian what is expected, so a form can highlight the field.
 */
final class RequestProblems {

    private static final Map<Integer, String> TYPES_BY_STATUS = Map.of(
            400, "bad-request",
            404, "not-found",
            405, "method-not-allowed",
            406, "not-acceptable",
            413, "payload-too-large",
            415, "unsupported-media-type");

    private RequestProblems() {
    }

    /** Errors of an unreadable body; empty when the JSON itself is malformed (no field to point at). */
    static List<ApiExceptionHandler.FieldErrorDto> unreadableBody(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            return List.of(new ApiExceptionHandler.FieldErrorDto(path(mismatch), expected(mismatch)));
        }
        return List.of();
    }

    /** {@code grid[2].pair}: property names joined by dots, list positions in brackets. */
    static String path(JacksonException ex) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : ex.getPath()) {
            if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    private static String expected(MismatchedInputException ex) {
        return expected(ex.getTargetType());
    }

    /** What the value should look like, by the Java type it is bound to. */
    static String expected(Class<?> type) {
        if (type == null) {
            return "Значення має неправильний тип або формат.";
        }
        if (type.isEnum()) {
            return "Допустимі значення: " + Arrays.stream(type.getEnumConstants()).map(Object::toString)
                    .collect(Collectors.joining(", ")) + ".";
        }
        if (type == LocalDate.class) {
            return "Очікується дата у форматі РРРР-ММ-ДД.";
        }
        if (type == LocalTime.class) {
            return "Очікується час у форматі ГГ:ХХ.";
        }
        if (type == YearMonth.class) {
            return "Очікується місяць у форматі РРРР-ММ.";
        }
        if (type == Instant.class) {
            return "Очікується момент часу у форматі ISO-8601.";
        }
        if (type == UUID.class) {
            return "Очікується ідентифікатор (UUID).";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "Очікується true або false.";
        }
        if (Number.class.isAssignableFrom(type) || (type.isPrimitive() && type != char.class)) {
            return "Очікується число.";
        }
        if (Collection.class.isAssignableFrom(type) || type.isArray()) {
            return "Очікується список.";
        }
        if (type == String.class) {
            return "Очікується текст.";
        }
        return "Очікується об'єкт.";
    }

    /** A stable {@code type} for framework errors that Spring leaves as {@code about:blank}. */
    static URI defaultType(HttpStatusCode status) {
        String slug = TYPES_BY_STATUS.getOrDefault(status.value(), "http-" + status.value());
        return URI.create("urn:rozklad:problem:" + slug);
    }
}
