package ua.vtc.rozklad.scheduler.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.util.DisconnectedClientHelper;

/** RFC 9457 ProblemDetail with Ukrainian titles; validation errors carry {@code errors[{field,message}]}. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String TYPE_PREFIX = "urn:rozklad:problem:";

    public record FieldErrorDto(String field, String message) {
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldErrorDto> errors = new ArrayList<>();
        for (ObjectError error : ex.getBindingResult().getAllErrors()) {
            String field = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
            errors.add(new FieldErrorDto(field, error.getDefaultMessage()));
        }
        return validationProblem(errors);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        List<FieldErrorDto> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String parameter = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                String field = error instanceof FieldError fieldError
                        ? parameter + "." + fieldError.getField()
                        : parameter;
                errors.add(new FieldErrorDto(field, error.getDefaultMessage()));
            }
        });
        return validationProblem(errors);
    }

    /** The security firewall rejected the request line ({@code /../}, {@code ;} parameters, …). */
    @ExceptionHandler(RequestRejectedException.class)
    ResponseEntity<Object> handleRejected(RequestRejectedException ex, HttpServletRequest request) {
        log.info("Request rejected by the firewall: {}", ex.getMessage());
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Адреса запиту містить недопустимі символи або сегменти шляху.");
        body.setType(URI.create(TYPE_PREFIX + "bad-request"));
        body.setTitle("Некоректний запит");
        addRequestId(body);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
        return validationProblem(ex.getConstraintViolations().stream()
                .map(violation -> new FieldErrorDto(parameterPath(violation), violation.getMessage()))
                .toList());
    }

    /** A query or path parameter that does not convert ({@code /jobs/abc}): 400 with the parameter as the field. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String field = ex.getPropertyName() == null ? "value" : ex.getPropertyName();
        return validationProblem(List.of(new FieldErrorDto(field, "Значення має неправильний формат.")));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Object> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Не знайдено", ex.getMessage(), request);
    }

    @ExceptionHandler(BusinessRuleException.class)
    ResponseEntity<Object> handleBusinessRule(BusinessRuleException ex, HttpServletRequest request) {
        ProblemDetail body = body(HttpStatus.UNPROCESSABLE_CONTENT, "business-rule", "Порушено правило",
                ex.getMessage(), request);
        body.setProperty("code", ex.getCode());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(body);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Object> handleStaleData(OptimisticLockingFailureException ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "stale-data", "Дані змінилися",
                "Запис щойно змінив інший користувач. Оновіть сторінку та повторіть дію.", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "data-integrity", "Конфлікт даних",
                "Операція порушує цілісність даних: такий запис уже існує або на нього посилаються інші дані.",
                request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "unauthorized", "Потрібна автентифікація",
                "Увійдіть у систему, щоб виконати цю дію.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", "Доступ заборонено", "Недостатньо прав для цієї дії.",
                request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
            return null;
        }
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Внутрішня помилка сервера",
                "Сталася непередбачена помилка. Спробуйте пізніше або повідомте адміністратора код запиту.",
                request);
    }

    /**
     * Field of a method-validation violation without the Java method name: {@code list.size} → {@code size}
     * (the request parameter), {@code update.body.rows[0].name} → {@code body.rows[0].name}.
     */
    static String parameterPath(ConstraintViolation<?> violation) {
        StringBuilder path = new StringBuilder();
        for (Path.Node node : violation.getPropertyPath()) {
            if (node.getKind() == ElementKind.METHOD || node.getKind() == ElementKind.CONSTRUCTOR) {
                continue;
            }
            if (node.getIndex() != null) {
                path.append('[').append(node.getIndex()).append(']');
            } else if (node.getKey() != null) {
                path.append('[').append(node.getKey()).append(']');
            }
            if (node.getName() != null && node.getKind() != ElementKind.CONTAINER_ELEMENT) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(node.getName());
            }
        }
        return path.isEmpty() ? violation.getPropertyPath().toString() : path.toString();
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode status,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            // помилки фреймворку (404 невідомого шляху, 405, 406 …) отримують сталий type, як в інших сервісах
            if (problem.getType() == null || "about:blank".equals(problem.getType().toString())) {
                problem.setType(URI.create(TYPE_PREFIX + switch (status.value()) {
                    case 400 -> "bad-request";
                    case 404 -> "not-found";
                    case 405 -> "method-not-allowed";
                    case 406 -> "not-acceptable";
                    case 415 -> "unsupported-media-type";
                    default -> "http-" + status.value();
                }));
            }
            addRequestId(problem);
        }
        return super.createResponseEntity(body, headers, status, request);
    }

    private ResponseEntity<Object> validationProblem(List<FieldErrorDto> errors) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Перевірте виділені поля.");
        body.setType(URI.create(TYPE_PREFIX + "validation"));
        body.setTitle("Некоректні дані");
        body.setProperty("errors", errors.stream()
                .sorted(Comparator.comparing(FieldErrorDto::field, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList());
        addRequestId(body);
        return ResponseEntity.badRequest().body(body);
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String type, String title, String detail,
                                                  HttpServletRequest request) {
        return ResponseEntity.status(status).body(body(status, type, title, detail, request));
    }

    private static ProblemDetail body(HttpStatus status, String type, String title, String detail,
                                      HttpServletRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create(TYPE_PREFIX + type));
        body.setTitle(title);
        body.setInstance(URI.create(request.getRequestURI()));
        addRequestId(body);
        return body;
    }

    private static void addRequestId(ProblemDetail problem) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
    }
}
