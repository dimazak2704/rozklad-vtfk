package ua.vtc.rozklad.gateway;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Gives every request an {@code X-Request-Id}: keeps a safe incoming value, otherwise generates a
 * UUID; forwards it to the service and returns it to the client. The id is also stored as the
 * exchange attribute {@value #ATTRIBUTE} for error responses.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdWebFilter implements WebFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = "rozklad.requestId";

    // захист від log injection у сервісах: лише короткі безпечні ідентифікатори
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId = resolve(exchange.getRequest().getHeaders().getFirst(HEADER));
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER, requestId))
                .build();
        ServerWebExchange mutated = exchange.mutate().request(request).build();
        mutated.getAttributes().put(ATTRIBUTE, requestId);
        mutated.getResponse().getHeaders().set(HEADER, requestId);
        return chain.filter(mutated);
    }

    static String resolve(String incoming) {
        if (incoming != null && SAFE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}
