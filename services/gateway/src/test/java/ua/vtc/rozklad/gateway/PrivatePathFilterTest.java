package ua.vtc.rozklad.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

/** Service-to-service paths are answered 404 at the edge in every spelling the services would accept. */
class PrivatePathFilterTest {

    private final PrivatePathFilter filter = new PrivatePathFilter(new GatewayErrorHandler(JsonMapper.builder().build()));

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/internal", "/api/internal/catalog/snapshot", "/api/internal/changes/0190f0a0/reply",
            "/api/internal/absences", "/api/internal/teachers/0190f0a0",
            "/api/admin/import", "/api/admin/import/catalog", "/api/admin/import/timetable",
            "/api/import/ai/parse-absence", "/api/import/ai/parse-absence/",
            // core decodes and matches the same way, so these reach the same handlers
            "/api/%69nternal/catalog/snapshot", "/api/admin/%69mport/load", "/API/Internal/absences"})
    void servicePathsAreNotFound(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.POST, URI.create(path)));
        boolean[] passed = {false};

        StepVerifier.create(filter.filter(exchange, ex -> {
            passed[0] = true;
            return Mono.empty();
        })).verifyComplete();

        assertThat(passed[0]).as("reached the route").isFalse();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(404);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body).contains("urn:rozklad:problem:not-found"))
                .verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/import/jobs", "/api/import/ai/usage", "/api/admin/imports", "/api/admin/teachers",
            "/api/internals", "/api/public/meta", "/api/notify/bindings/me", "/api/documents/orders/x.pdf"})
    void browserPathsPass(String path) {
        assertThat(PrivatePathFilter.isPrivate(PathContainer.parsePath(path))).isFalse();
    }
}
