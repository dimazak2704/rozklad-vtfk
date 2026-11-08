package ua.vtc.rozklad.gateway;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Routes match the raw path, while the services normalize it: {@code /api//public/meta} missed the
 * rate-limited route {@code /api/public/**}, fell through to the catch-all {@code /api/**} and was
 * served by core as {@code /api/public/meta} without a limit. A path that is not in normal form
 * (an empty segment, a {@code .} or {@code ..} segment, an encoded dot, slash or backslash, a
 * backslash or a {@code ;} parameter) is rejected with 400 before routing, so every route sees
 * exactly the path the service will serve. The services' firewalls reject the same paths.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class NormalizedPathFilter implements WebFilter {

    private static final Pattern NOT_NORMAL = Pattern.compile("//|/\\.{1,2}(/|$)|%2e|%2f|%5c|\\\\|;");

    private final GatewayErrorHandler errors;

    public NormalizedPathFilter(GatewayErrorHandler errors) {
        this.errors = errors;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String rawPath = exchange.getRequest().getURI().getRawPath();
        if (rawPath != null && !isNormal(rawPath)) {
            return errors.write(exchange, exchange.getResponse(), new GatewayErrorHandler.Problem(
                    HttpStatus.BAD_REQUEST, "bad-request", "Некоректний запит",
                    "Шлях запиту має недопустимий вигляд (порожній сегмент, «.» або «..», закодована скісна риска)."));
        }
        return chain.filter(exchange);
    }

    static boolean isNormal(String rawPath) {
        return !NOT_NORMAL.matcher(rawPath.toLowerCase(Locale.ROOT)).find();
    }
}
