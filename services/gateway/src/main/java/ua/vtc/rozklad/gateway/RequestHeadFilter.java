package ua.vtc.rozklad.gateway;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Limits of the request line and the headers with a ProblemDetail answer (414, 431). Netty's own
 * decoder limits ({@code server.netty.max-initial-line-length}, {@code server.max-http-request-header-size})
 * are set well above these, so a request between the two reaches this filter instead of getting
 * Netty's empty 414/431; only a request above Netty's limits still gets the empty answer. The limits
 * here are the usual 4 KB address and 8 KB of headers, which the services behind (Tomcat) accept too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class RequestHeadFilter implements WebFilter {

    private final GatewayErrorHandler errors;
    private final long maxUri;
    private final long maxHeaders;

    public RequestHeadFilter(GatewayErrorHandler errors,
                             @Value("${app.gateway.max-uri-length:4KB}") DataSize maxUri,
                             @Value("${app.gateway.max-header-size:8KB}") DataSize maxHeaders) {
        this.errors = errors;
        this.maxUri = maxUri.toBytes();
        this.maxHeaders = maxHeaders.toBytes();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        URI uri = exchange.getRequest().getURI();
        long uriLength = length(uri.getRawPath()) + (uri.getRawQuery() == null ? 0 : 1 + length(uri.getRawQuery()));
        if (uriLength > maxUri) {
            return errors.write(exchange, exchange.getResponse(), new GatewayErrorHandler.Problem(HttpStatus.URI_TOO_LONG,
                    "uri-too-long", "Адреса задовга", "Адреса запиту довша за %d байтів.".formatted(maxUri)));
        }
        long headers = 0;
        for (Map.Entry<String, List<String>> header : exchange.getRequest().getHeaders().headerSet()) {
            for (String value : header.getValue()) {
                headers += length(header.getKey()) + length(value) + 4;
            }
        }
        if (headers > maxHeaders) {
            return errors.write(exchange, exchange.getResponse(), new GatewayErrorHandler.Problem(
                    HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE, "request-header-fields-too-large",
                    "Заголовки запиту завеликі", "Заголовки запиту більші за %d КБ.".formatted(maxHeaders / 1024)));
        }
        return chain.filter(exchange);
    }

    private static long length(String text) {
        return text == null ? 0 : text.length();
    }
}
