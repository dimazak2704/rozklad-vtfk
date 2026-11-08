package ua.vtc.rozklad.core.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.hibernate.JDBCException;
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
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.firewall.RequestRejectedException;
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
import ua.vtc.rozklad.core.shared.web.RequestIdFilter;
import ua.vtc.rozklad.core.shared.web.SupportedDates;

/**
 * Turns every error into an RFC 9457 ProblemDetail with Ukrainian {@code title}/{@code detail}.
 * Validation errors carry {@code errors[]}, schedule conflicts {@code conflicts[]}, business rule
 * violations {@code code}; every problem carries {@code requestId} for support. A body or query
 * parameter that cannot be bound (wrong type or format) is a validation error of that field too.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

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
            // елемент списку чи мапи в тілі (List<@Valid …>) — з індексом: grid[2].pair
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
        List<FieldErrorDto> errors = RequestProblems.unreadableBody(ex, RequestProblems.listBodyName(request));
        return validationProblem(errors, errors.isEmpty()
                ? "Тіло запиту не вдалося прочитати: перевірте формат JSON." : "Перевірте виділені поля.");
    }

    /** A query or path parameter that does not convert ({@code ?from=abc}). */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String field = ex.getPropertyName() == null ? "value" : ex.getPropertyName();
        String message = RequestProblems.expected(ex);
        return validationProblem(List.of(new FieldErrorDto(field, message)), SupportedDates.MESSAGE.equals(message)
                ? "Значення «%s» параметра «%s» поза підтримуваним діапазоном дат.".formatted(ex.getValue(), field)
                : "Значення «%s» параметра «%s» має неправильний формат.".formatted(ex.getValue(), field));
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
                                                                          HttpHeaders headers,
                                                                          HttpStatusCode status,
                                                                          WebRequest request) {
        return validationProblem(List.of(new FieldErrorDto(ex.getParameterName(), "Обов'язковий параметр.")),
                "Обов'язковий параметр «%s» відсутній.".formatted(ex.getParameterName()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldErrorDto> errors = ex.getConstraintViolations().stream()
                .map(violation -> new FieldErrorDto(violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        return validationProblem(errors);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Object> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND, "Не знайдено", ex.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Object> handleConflict(ConflictException ex, HttpServletRequest request) {
        if (ex.getConflicts().isEmpty()) {
            return problem(HttpStatus.CONFLICT, ProblemTypes.CONFLICT, "Конфлікт", ex.getMessage(), request);
        }
        ProblemDetail body = body(HttpStatus.CONFLICT, ProblemTypes.SCHEDULE_CONFLICT, "Конфлікти розкладу",
                ex.getMessage(), request);
        body.setProperty("conflicts", ex.getConflicts());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(BusinessRuleException.class)
    ResponseEntity<Object> handleBusinessRule(BusinessRuleException ex, HttpServletRequest request) {
        ProblemDetail body = body(HttpStatus.UNPROCESSABLE_CONTENT, ProblemTypes.BUSINESS_RULE,
                "Порушено правило", ex.getMessage(), request);
        body.setProperty("code", ex.getCode());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(body);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Object> handleStaleData(OptimisticLockingFailureException ex, HttpServletRequest request) {
        log.info("Optimistic lock conflict: {}", ex.getMessage());
        return problem(HttpStatus.CONFLICT, ProblemTypes.STALE_DATA, "Дані змінилися",
                "Запис щойно змінив інший користувач. Оновіть сторінку та повторіть дію.", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        // деталі SQL не віддаємо клієнту (вони розкривають схему БД) і не пишемо в журнал: повідомлення
        // PostgreSQL містить значення рядка (коментарі відсутностей, назви), ARCHITECTURE §10
        log.warn("Data integrity violation: {}", describe(ex));
        if (isDataException(ex)) {
            // SQLState класу 22 (значення поза діапазоном типу, занадто довгий текст): це не «запис уже
            // існує», а некоректне значення; поля, яке його дало, тут уже не видно
            return problem(HttpStatus.BAD_REQUEST, ProblemTypes.BAD_REQUEST, "Некоректні дані",
                    "Одне зі значень запиту виходить за допустимі межі. Перевірте дати й числа.", request);
        }
        return problem(HttpStatus.CONFLICT, ProblemTypes.DATA_INTEGRITY, "Конфлікт даних",
                "Операція порушує цілісність даних: такий запис уже існує або на нього посилаються інші дані.",
                request);
    }

    /** The security firewall rejected the request line (path traversal, encoded slash, …). */
    @ExceptionHandler(RequestRejectedException.class)
    ResponseEntity<Object> handleRejected(RequestRejectedException ex, HttpServletRequest request) {
        log.info("Request rejected by the firewall: {}", ex.getMessage());
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Адреса запиту містить недопустимі символи або сегменти шляху.");
        body.setType(ProblemTypes.BAD_REQUEST);
        body.setTitle("Некоректний запит");
        addRequestId(body);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, ProblemTypes.UNAUTHORIZED, "Потрібна автентифікація",
                "Увійдіть у систему, щоб виконати цю дію.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, ProblemTypes.FORBIDDEN, "Доступ заборонено",
                "Недостатньо прав для цієї дії.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
            log.debug("Client disconnected: {}", ex.getMessage());
            return null;
        }
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ProblemTypes.INTERNAL, "Внутрішня помилка сервера",
                "Сталася непередбачена помилка. Спробуйте пізніше або повідомте адміністратора код запиту.",
                request);
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

    /** SQLState class 22 «data exception» (PostgreSQL: date or number out of range, value too long). */
    static boolean isDataException(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof JDBCException jdbc) {
                return jdbc.getSQLState() != null && jdbc.getSQLState().startsWith("22");
            }
            if (cause instanceof SQLException sql) {
                return sql.getSQLState() != null && sql.getSQLState().startsWith("22");
            }
        }
        return false;
    }

    /** SQLState and constraint only: never the message, which carries row values. */
    static String describe(DataIntegrityViolationException ex) {
        Throwable cause = ex.getCause();
        while (cause != null && !(cause instanceof JDBCException)) {
            cause = cause.getCause();
        }
        if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
            return "SQLState " + violation.getSQLState() + ", constraint " + violation.getConstraintName();
        }
        if (cause instanceof JDBCException jdbc) {
            return "SQLState " + jdbc.getSQLState() + " (" + jdbc.getClass().getSimpleName() + ")";
        }
        if (ex.getMostSpecificCause() instanceof SQLException sql) {
            return "SQLState " + sql.getSQLState();
        }
        return ex.getClass().getSimpleName();
    }

    private ResponseEntity<Object> validationProblem(List<FieldErrorDto> errors) {
        return validationProblem(errors, "Перевірте виділені поля.");
    }

    private ResponseEntity<Object> validationProblem(List<FieldErrorDto> errors, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        body.setType(ProblemTypes.VALIDATION);
        body.setTitle("Некоректні дані");
        body.setProperty("errors", errors.stream()
                .sorted(Comparator.comparing(FieldErrorDto::field, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList());
        addRequestId(body);
        return ResponseEntity.badRequest().body(body);
    }

    private static ResponseEntity<Object> problem(HttpStatus status, URI type, String title, String detail,
                                                  HttpServletRequest request) {
        return ResponseEntity.status(status).body(body(status, type, title, detail, request));
    }

    private static ProblemDetail body(HttpStatus status, URI type, String title, String detail,
                                      HttpServletRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(type);
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
