package ua.vtc.rozklad.gateway;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Errors raised by the gateway itself (no route, service down, timeout) as RFC 9457
 * ProblemDetail with Ukrainian texts, the same shape the services use. Gateway filters that only
 * set a status (the rate limiter's 429) get the same body through {@link EmptyErrorBodyFilter}.
 */
@Component
@Order(-2)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final JsonMapper jsonMapper;

    public GatewayErrorHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(error);
        }
        Problem problem = classify(error);
        if (problem.status().is5xxServerError()) {
            log.warn("Gateway error {} on {} {}: {}", problem.status().value(), exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath(), error.toString());
        }
        return write(exchange, response, problem);
    }

    /** Writes {@code problem} as {@code application/problem+json} into {@code response}. */
    Mono<Void> write(ServerWebExchange exchange, ServerHttpResponse response, Problem problem) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "urn:rozklad:problem:" + problem.type());
        body.put("title", problem.title());
        body.put("status", problem.status().value());
        body.put("detail", problem.detail());
        body.put("instance", exchange.getRequest().getPath().value());
        Object requestId = exchange.getAttribute(RequestIdWebFilter.ATTRIBUTE);
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        byte[] bytes = jsonMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        response.setStatusCode(problem.status());
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    static Problem classify(Throwable error) {
        // the service is down: refused connection, no route, or (Docker) its container name no longer resolves
        if (hasCause(error, ConnectException.class) || hasCause(error, UnknownHostException.class)
                || hasCause(error, NoRouteToHostException.class)) {
            return new Problem(HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable", "Сервіс тимчасово недоступний",
                    "Спробуйте ще раз за хвилину.");
        }
        if (hasCause(error, TimeoutException.class)) {
            return new Problem(HttpStatus.GATEWAY_TIMEOUT, "gateway-timeout", "Сервіс не відповів вчасно",
                    "Спробуйте ще раз пізніше.");
        }
        if (error instanceof ResponseStatusException statusException) {
            return fromStatus(statusException.getStatusCode());
        }
        return new Problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Внутрішня помилка сервера",
                "Сталася непередбачена помилка. Спробуйте пізніше.");
    }

    static Problem fromStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> new Problem(HttpStatus.NOT_FOUND, "not-found", "Не знайдено", "Такого ресурсу не існує.");
            case 429 -> new Problem(HttpStatus.TOO_MANY_REQUESTS, "too-many-requests", "Забагато запитів",
                    "Зачекайте секунду й повторіть запит.");
            case 403 -> new Problem(HttpStatus.FORBIDDEN, "forbidden", "Доступ заборонено",
                    "Запит з цього джерела (Origin) не дозволено.");
            case 405 -> new Problem(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed", "Метод не підтримується",
                    "Цей метод не підтримується для ресурсу.");
            case 503 -> new Problem(HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable",
                    "Сервіс тимчасово недоступний", "Спробуйте ще раз за хвилину.");
            case 504 -> new Problem(HttpStatus.GATEWAY_TIMEOUT, "gateway-timeout", "Сервіс не відповів вчасно",
                    "Спробуйте ще раз пізніше.");
            default -> {
                HttpStatus resolved = HttpStatus.resolve(status.value());
                HttpStatus effective = resolved != null ? resolved : HttpStatus.INTERNAL_SERVER_ERROR;
                yield effective.is4xxClientError()
                        ? new Problem(effective, "bad-request", "Некоректний запит", "Перевірте запит.")
                        : new Problem(effective, "internal", "Внутрішня помилка сервера",
                        "Сталася непередбачена помилка. Спробуйте пізніше.");
            }
        };
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    record Problem(HttpStatus status, String type, String title, String detail) {
    }
}
