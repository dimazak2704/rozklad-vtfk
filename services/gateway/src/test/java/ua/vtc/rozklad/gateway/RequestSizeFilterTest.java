package ua.vtc.rozklad.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.unit.DataSize;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

/** A chunked body (no Content-Length) is counted while it streams and stopped at the limit. */
class RequestSizeFilterTest {

    private final RequestSizeFilter filter = new RequestSizeFilter(new GatewayErrorHandler(JsonMapper.builder().build()),
            DataSize.ofBytes(10), DataSize.ofBytes(100));

    @Test
    void chunkedBodyAboveTheLimitFailsWhileStreaming() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .method(HttpMethod.POST, "/api/public/invites/x/accept").body(chunks("{\"a\":", "\"0123456789\"}")));

        StepVerifier.create(filter.filter(exchange, consumingBody()))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(RequestSizeFilter.BodyTooLargeException.class);
                    assertThat(GatewayErrorHandler.classify(error).status().value()).isEqualTo(413);
                })
                .verify();
    }

    @Test
    void chunkedBodyWithinTheLimitPasses() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .method(HttpMethod.POST, "/api/import/jobs").body(chunks("0123456789", "0123456789")));

        StepVerifier.create(filter.filter(exchange, consumingBody())).verifyComplete();
    }

    private static Flux<DataBuffer> chunks(String... parts) {
        return Flux.fromArray(parts)
                .map(part -> DefaultDataBufferFactory.sharedInstance.wrap(part.getBytes(StandardCharsets.UTF_8)));
    }

    private static org.springframework.web.server.WebFilterChain consumingBody() {
        return exchange -> exchange.getRequest().getBody().then(Mono.empty());
    }
}
