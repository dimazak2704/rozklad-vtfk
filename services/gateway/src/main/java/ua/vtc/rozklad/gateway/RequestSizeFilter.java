package ua.vtc.rozklad.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Upper bound for request bodies before they reach a service. Uploads of the importer
 * ({@code /api/import/**}: files up to 20 MB, multipart limit 22 MB) get {@code app.gateway.max-upload-size},
 * every other API body (JSON of at most a few hundred kilobytes) {@code app.gateway.max-body-size}.
 * A declared {@code Content-Length} above the limit is answered 413 at once; a chunked body is
 * counted while it streams and cut off at the limit. Without this an anonymous client could make
 * core read and parse a 30 MB JSON on a public route.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class RequestSizeFilter implements WebFilter {

    private final GatewayErrorHandler errors;
    private final long maxBody;
    private final long maxUpload;

    public RequestSizeFilter(GatewayErrorHandler errors,
                             @Value("${app.gateway.max-body-size:2MB}") DataSize maxBody,
                             @Value("${app.gateway.max-upload-size:25MB}") DataSize maxUpload) {
        this.errors = errors;
        this.maxBody = maxBody.toBytes();
        this.maxUpload = maxUpload.toBytes();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        long limit = request.getPath().value().startsWith("/api/import/") ? maxUpload : maxBody;
        long declared = request.getHeaders().getContentLength();
        if (declared > limit) {
            return errors.write(exchange, exchange.getResponse(), tooLarge(limit));
        }
        if (declared >= 0) {
            return chain.filter(exchange);
        }
        return chain.filter(exchange.mutate().request(new LimitedBody(request, limit)).build());
    }

    static GatewayErrorHandler.Problem tooLarge(long limit) {
        return new GatewayErrorHandler.Problem(HttpStatus.CONTENT_TOO_LARGE, "payload-too-large",
                "Запит завеликий", "Тіло запиту більше за %d МБ.".formatted(Math.max(1, limit / (1024 * 1024))));
    }

    /** A chunked body that fails with {@link BodyTooLargeException} once it passes the limit. */
    private static final class LimitedBody extends ServerHttpRequestDecorator {

        private final long limit;

        LimitedBody(ServerHttpRequest delegate, long limit) {
            super(delegate);
            this.limit = limit;
        }

        @Override
        public Flux<DataBuffer> getBody() {
            long[] seen = {0};
            return super.getBody().handle((buffer, sink) -> {
                seen[0] += buffer.readableByteCount();
                if (seen[0] > limit) {
                    DataBufferUtils.release(buffer);
                    sink.error(new BodyTooLargeException(limit));
                } else {
                    sink.next(buffer);
                }
            });
        }
    }

    /** Mapped to 413 by {@link GatewayErrorHandler#classify}. */
    static final class BodyTooLargeException extends RuntimeException {

        private final long limit;

        BodyTooLargeException(long limit) {
            super("request body larger than " + limit + " bytes", null, false, false);
            this.limit = limit;
        }

        long limit() {
            return limit;
        }
    }
}
