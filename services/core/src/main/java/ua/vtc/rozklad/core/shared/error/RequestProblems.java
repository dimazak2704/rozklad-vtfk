package ua.vtc.rozklad.core.shared.error;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.InputCoercionException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;
import ua.vtc.rozklad.core.shared.web.NulFreeStringDeserializer;
import ua.vtc.rozklad.core.shared.web.SupportedDates;

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

    static final String OUT_OF_RANGE = "Число поза допустимим діапазоном.";

    private static final Set<Class<?>> INTEGRAL = Set.of(Integer.class, int.class, Long.class, long.class,
            Short.class, short.class, Byte.class, byte.class);

    private RequestProblems() {
    }

    /**
     * Errors of an unreadable body; empty when the JSON itself is malformed (no field to point at).
     * {@code listName}: the parameter name of a body that is a JSON array, so the field reads
     * {@code grid[0].kind} like the bean validation errors of the same body.
     */
    static List<FieldErrorDto> unreadableBody(HttpMessageNotReadableException ex, String listName) {
        Throwable cause = ex.getCause();
        if (cause instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            return List.of(new FieldErrorDto(field(path(mismatch), listName), expected(mismatch)));
        }
        // число, що не влазить у поле (2147483648 для int): помилка парсера, але поле відоме
        if (cause instanceof InputCoercionException overflow && !overflow.getPath().isEmpty()) {
            return List.of(new FieldErrorDto(field(path(overflow), listName), OUT_OF_RANGE));
        }
        return List.of();
    }

    private static String field(String path, String listName) {
        return listName != null && path.startsWith("[") ? listName + path : path;
    }

    /** Name of the {@code @RequestBody} parameter of the handler when the body is a list or an array. */
    static String listBodyName(WebRequest request) {
        if (request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST)
                instanceof HandlerMethod handler) {
            for (MethodParameter parameter : handler.getMethodParameters()) {
                Class<?> type = parameter.getParameterType();
                if (parameter.hasParameterAnnotation(RequestBody.class)
                        && (Collection.class.isAssignableFrom(type) || type.isArray())) {
                    parameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
                    return parameter.getParameterName();
                }
            }
        }
        return null;
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
        if (ex instanceof InvalidFormatException && ex.getTargetType() == String.class) {
            return NulFreeStringDeserializer.MESSAGE;
        }
        if (ex instanceof InvalidFormatException && SupportedDates.MESSAGE.equals(ex.getOriginalMessage())) {
            return SupportedDates.MESSAGE;
        }
        return expected(ex.getTargetType());
    }

    /** Message of a query parameter that failed conversion: the range rule, else the expected format. */
    static String expected(TypeMismatchException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SupportedDates.UnsupportedDateException) {
                return SupportedDates.MESSAGE;
            }
        }
        return expected(ex.getRequiredType());
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
        if (INTEGRAL.contains(type)) {
            return "Очікується ціле число.";
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
