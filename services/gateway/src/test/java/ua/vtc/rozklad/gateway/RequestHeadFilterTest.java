package ua.vtc.rozklad.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

/** An over-long address or oversized headers get 414/431 with a ProblemDetail, not Netty's empty answer. */
class RequestHeadFilterTest {

    private final RequestHeadFilter filter = new RequestHeadFilter(new GatewayErrorHandler(JsonMapper.builder().build()),
            DataSize.ofBytes(64), DataSize.ofBytes(200));

    @Test
    void oversizedHeadersAreA431Problem() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/public/meta")
                .header("X-Big", "x".repeat(300)));

        StepVerifier.create(filter.filter(exchange, passing())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(431);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body).contains("urn:rozklad:problem:request-header-fields-too-large"))
                .verifyComplete();
    }

    @Test
    void overLongAddressIsA414Problem() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/public/search?q=" + "a".repeat(100)));

        StepVerifier.create(filter.filter(exchange, passing())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(414);
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body).contains("urn:rozklad:problem:uri-too-long"))
                .verifyComplete();
    }

    @Test
    void ordinaryRequestPasses() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/public/meta")
                .header("Authorization", "Bearer abc"));
        boolean[] passed = {false};

        StepVerifier.create(filter.filter(exchange, ex -> {
            passed[0] = true;
            return Mono.empty();
        })).verifyComplete();

        assertThat(passed[0]).isTrue();
    }

    private static WebFilterChain passing() {
        return exchange -> Mono.error(new AssertionError("the request must not pass"));
    }
}
