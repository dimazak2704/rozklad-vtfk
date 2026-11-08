package ua.vtc.rozklad.notification.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import java.net.URI;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.util.DisconnectedClientHelper;
import ua.vtc.rozklad.notification.core.UpstreamRejectedException;
import ua.vtc.rozklad.notification.core.UpstreamUnavailableException;
import ua.vtc.rozklad.notification.documents.ConverterUnavailableException;
import ua.vtc.rozklad.notification.documents.DocumentRenderingException;

/** RFC 9457 ProblemDetail with Ukrainian titles; validation errors carry {@code errors[{field,message}]}. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String TYPE_PREFIX = "urn:rozklad:problem:";

    /** Tomcat names the parameter it could not decode: «… Parameter [q] with value […] …». */
    private static final Pattern PARAMETER_NAME = Pattern.compile("Parameter \\[([^\\]]{1,100})]");

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
            // елемент списку чи мапи в тілі (List<@Valid …>) — з індексом: items[2].code
            String parameter = result.getMethodParameter().getParameterName()
                    + (result.getContainerIndex() != null ? "[" + result.getContainerIndex() + "]"
                    : result.getContainerKey() != null ? "[" + result.getContainerKey() + "]" : "");
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                String field = error instanceof FieldError fieldError
                        ? parameter + "." + fieldError.getField()
                        : parameter;
                errors.add(new FieldErrorDto(field, error.getDefaultMessage()));
            }
        });
        return validationProblem(errors);
    }

    /** Malformed JSON or a value of the wrong type or format: 400 with the field when it is known. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldErrorDto> errors = RequestProblems.unreadableBody(ex);
        return validationProblem(errors, errors.isEmpty()
                ? "Тіло запиту не вдалося прочитати: перевірте формат JSON." : "Перевірте виділені поля.");
    }

    /** A query or path parameter that does not convert ({@code ?status=NOPE}). */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String field = ex.getPropertyName() == null ? "value" : ex.getPropertyName();
        return validationProblem(List.of(new FieldErrorDto(field, RequestProblems.expected(ex.getRequiredType()))),
                "Значення «%s» параметра «%s» має неправильний формат.".formatted(ex.getValue(), field));
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
                                                                          HttpHeaders headers,
                                                                          HttpStatusCode status,
                                                                          WebRequest request) {
        return validationProblem(List.of(new FieldErrorDto(ex.getParameterName(), "Обов'язковий параметр.")),
                "Обов'язковий параметр «%s» відсутній.".formatted(ex.getParameterName()));
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

    /**
     * Tomcat could not decode a query parameter ({@code ?kind=%E0%A4} is not UTF-8): a client
     * error naming the parameter, never a 500 (the value itself is not echoed).
     */
    @ExceptionHandler(InvalidParameterException.class)
    ResponseEntity<Object> handleUndecodableParameter(InvalidParameterException ex, HttpServletRequest request) {
        log.info("Undecodable request parameter on {} {}", request.getMethod(), request.getRequestURI());
        Matcher name = PARAMETER_NAME.matcher(String.valueOf(ex.getMessage()));
        if (name.find()) {
            String field = name.group(1);
            return validationProblem(List.of(new FieldErrorDto(field,
                            "Значення не вдалося розкодувати: очікується текст у кодуванні UTF-8.")),
                    "Параметр «%s» має некоректне кодування.".formatted(field));
        }
        return problem(HttpStatus.BAD_REQUEST, "bad-request", "Некоректний запит",
                "Параметри запиту не вдалося розкодувати: очікується текст у кодуванні UTF-8.", request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
        return validationProblem(ex.getConstraintViolations().stream()
                .map(violation -> new FieldErrorDto(parameterPath(violation), violation.getMessage()))
                .toList());
    }

    @ExceptionHandler(FieldValidationException.class)
    ResponseEntity<Object> handleFieldValidation(FieldValidationException ex) {
        return validationProblem(List.of(new FieldErrorDto(ex.getField(), ex.getMessage())));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Object> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Не знайдено", ex.getMessage(), request);
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    ResponseEntity<Object> handleForbiddenOperation(ForbiddenOperationException ex, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", "Доступ заборонено", ex.getMessage(), request);
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    ResponseEntity<Object> handleServiceUnavailable(ServiceUnavailableException ex, HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable", "Сервіс тимчасово недоступний",
                ex.getMessage(), request);
    }

    @ExceptionHandler(UpstreamUnavailableException.class)
    ResponseEntity<Object> handleUpstreamUnavailable(UpstreamUnavailableException ex, HttpServletRequest request) {
        log.warn("Upstream {} unavailable: {}", ex.service(), ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable", "Сервіс тимчасово недоступний",
                "Сервіс розкладу тимчасово недоступний. Спробуйте за хвилину.", request);
    }

    @ExceptionHandler(UpstreamRejectedException.class)
    ResponseEntity<Object> handleUpstreamRejected(UpstreamRejectedException ex, HttpServletRequest request) {
        log.warn("Upstream rejected a call of notification: {}", ex.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, "bad-gateway", "Помилка суміжного сервісу",
                "Сервіс розкладу відхилив запит notification. Повідомте адміністратора код запиту.", request);
    }

    @ExceptionHandler(ConverterUnavailableException.class)
    ResponseEntity<Object> handleConverterUnavailable(ConverterUnavailableException ex, HttpServletRequest request) {
        log.warn("{}", ex.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable", ConverterUnavailableException.MESSAGE,
                "Конвертер PDF недоступний: на цьому сервері немає LibreOffice. DOCX доступний.", request);
    }

    @ExceptionHandler(DocumentRenderingException.class)
    ResponseEntity<Object> handleDocumentRendering(DocumentRenderingException ex, HttpServletRequest request) {
        log.error("Document rendering failed: {}", ex.getMessage(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "document-rendering", "Не вдалося сформувати документ",
                "Не вдалося сформувати документ. Спробуйте ще раз або завантажте DOCX.", request);
    }

    @ExceptionHandler(BadGatewayException.class)
    ResponseEntity<Object> handleBadGateway(BadGatewayException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_GATEWAY, "bad-gateway", "Помилка суміжного сервісу", ex.getMessage(), request);
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
        // повідомлення PostgreSQL містить значення рядка: у журнал — лише SQLState (ARCHITECTURE §10)
        String state = sqlState(ex);
        log.warn("Data integrity violation: SQLState {}", state);
        if (state != null && state.startsWith("22")) {
            // клас 22 «data exception» (недопустимий символ, значення поза типом): некоректне значення,
            // а не «запис уже існує»
            return problem(HttpStatus.BAD_REQUEST, "bad-request", "Некоректні дані",
                    "Одне зі значень запиту не можна зберегти: перевірте текст, дати й числа.", request);
        }
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
            if (problem.getType() == null || "about:blank".equals(problem.getType().toString())) {
                problem.setType(RequestProblems.defaultType(status));
            }
            addRequestId(problem);
        }
        return super.createResponseEntity(body, headers, status, request);
    }

    private ResponseEntity<Object> validationProblem(List<FieldErrorDto> errors) {
        return validationProblem(errors, "Перевірте виділені поля.");
    }

    private ResponseEntity<Object> validationProblem(List<FieldErrorDto> errors, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
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

    /** SQLState of the database error behind the exception, or {@code null}. */
    static String sqlState(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

}
