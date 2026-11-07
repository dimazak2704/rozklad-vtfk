package ua.vtc.rozklad.notification.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
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
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
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

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
        return validationProblem(ex.getConstraintViolations().stream()
                .map(violation -> new FieldErrorDto(violation.getPropertyPath().toString(), violation.getMessage()))
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

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode status,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail problem) {
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
