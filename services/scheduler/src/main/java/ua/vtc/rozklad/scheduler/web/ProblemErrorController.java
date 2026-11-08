package ua.vtc.rozklad.scheduler.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The servlet container's error page ({@code /error}): errors Tomcat or a filter raised outside
 * Spring MVC (a {@code TRACE} request Tomcat refuses with 405, an exception in a filter) are RFC 9457
 * ProblemDetail like every other error of the API, with a stable {@code type} and the
 * {@code requestId}, instead of Spring Boot's default JSON ({@code timestamp, status, error, path}).
 */
@RestController
@RequestMapping("${server.error.path:/error}")
public class ProblemErrorController implements ErrorController {

    private static final String TYPE_PREFIX = "urn:rozklad:problem:";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final Map<Integer, String> TYPES = Map.ofEntries(
            Map.entry(400, "bad-request"),
            Map.entry(401, "unauthorized"),
            Map.entry(403, "forbidden"),
            Map.entry(404, "not-found"),
            Map.entry(405, "method-not-allowed"),
            Map.entry(406, "not-acceptable"),
            Map.entry(413, "payload-too-large"),
            Map.entry(415, "unsupported-media-type"),
            Map.entry(500, "internal"));

    private static final Map<Integer, String> DETAILS = Map.of(
            400, "Запит має неправильний вигляд.",
            401, "Увійдіть у систему, щоб виконати цю дію.",
            403, "Недостатньо прав для цієї дії.",
            404, "Такої адреси немає.",
            405, "Цей метод запиту для цієї адреси не підтримується.",
            500, "Сервер не зміг обробити запит. Спробуйте ще раз пізніше.");

    @RequestMapping
    public ResponseEntity<ProblemDetail> error(HttpServletRequest request, HttpServletResponse response) {
        HttpStatus status = status(request);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status,
                DETAILS.getOrDefault(status.value(), "Запит не вдалося виконати."));
        body.setType(URI.create(TYPE_PREFIX + TYPES.getOrDefault(status.value(), "http-" + status.value())));
        // адреса, на яку прийшов запит, а не сторінка помилки /error
        if (request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String path && path.startsWith("/")) {
            try {
                body.setInstance(URI.create(path));
            } catch (IllegalArgumentException unparsable) {
                body.setInstance(null);
            }
        }
        String requestId = requestId(request);
        body.setProperty("requestId", requestId);
        if (!response.containsHeader(REQUEST_ID_HEADER)) {
            response.setHeader(REQUEST_ID_HEADER, requestId);
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private static HttpStatus status(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = code instanceof Integer value ? HttpStatus.resolve(value) : null;
        return status == null || !status.isError() ? HttpStatus.INTERNAL_SERVER_ERROR : status;
    }

    /** The id the request filter chose, else the gateway's header, else a new one. */
    private static String requestId(HttpServletRequest request) {
        if (request.getAttribute("requestId") instanceof String stored) {
            return stored;
        }
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        return incoming != null && SAFE_ID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
    }
}
