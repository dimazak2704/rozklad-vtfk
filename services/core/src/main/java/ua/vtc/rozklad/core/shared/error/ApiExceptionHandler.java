package ua.vtc.rozklad.core.shared.error;

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
import ua.vtc.rozklad.core.shared.web.RequestIdFilter;

/**
 * Turns every error into an RFC 9457 ProblemDetail with Ukrainian {@code title}/{@code detail}.
 * Validation errors carry {@code errors[]}, schedule conflicts {@code conflicts[]}, business rule
 * violations {@code code}; every problem carries {@code requestId} for support.
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
        // деталі SQL не віддаємо клієнту: вони розкривають схему БД
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, ProblemTypes.DATA_INTEGRITY, "Конфлікт даних",
                "Операція порушує цілісність даних: такий запис уже існує або на нього посилаються інші дані.",
                request);
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
            addRequestId(problem);
        }
        return super.createResponseEntity(body, headers, status, request);
    }

    private ResponseEntity<Object> validationProblem(List<FieldErrorDto> errors) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Перевірте виділені поля.");
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
