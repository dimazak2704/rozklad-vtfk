package ua.vtc.rozklad.gateway;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * A gateway filter that rejects a request by status only (the Redis rate limiter answers 429 and
 * completes the response) would send an empty body. Such a response gets the gateway's
 * ProblemDetail instead, and a 429 also tells when to retry ({@code Retry-After}). The CORS check
 * of the handler mapping only sets 403 and leaves completing the response to the server, so after
 * the chain an error status without a body gets the ProblemDetail too. Proxied responses are
 * written by the routing filter (committed) and pass through unchanged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class EmptyErrorBodyFilter implements WebFilter {

    /** The public bucket refills {@code PUBLIC_RATE_LIMIT_PER_SECOND} tokens a second. */
    static final String RETRY_AFTER_SECONDS = "1";

    private final GatewayErrorHandler errors;

    public EmptyErrorBodyFilter(GatewayErrorHandler errors) {
        this.errors = errors;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpResponse response = exchange.getResponse();
        ServerHttpResponseDecorator withProblem = new ServerHttpResponseDecorator(response) {
            @Override
            public Mono<Void> setComplete() {
                HttpStatusCode status = getStatusCode();
                if (status == null || !status.isError() || isCommitted()) {
                    return super.setComplete();
                }
                if (status.value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                    getHeaders().set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
                }
                return errors.write(exchange, getDelegate(), GatewayErrorHandler.fromStatus(status));
            }
        };
        return chain.filter(exchange.mutate().response(withProblem).build())
                .then(Mono.defer(() -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (status == null || !status.isError() || response.isCommitted()) {
                        return Mono.empty();
                    }
                    return errors.write(exchange, response, GatewayErrorHandler.fromStatus(status));
                }));
    }
}
