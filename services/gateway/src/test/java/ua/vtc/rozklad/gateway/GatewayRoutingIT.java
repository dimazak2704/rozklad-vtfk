package ua.vtc.rozklad.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Routes, request ids, CORS, SSE streaming and error format of the gateway against fake services. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "GATEWAY_RESPONSE_TIMEOUT=1s")
@AutoConfigureWebTestClient
@Import(RedisTestcontainersConfiguration.class)
class GatewayRoutingIT {

    private static final FakeService CORE = new FakeService("core");
    private static final FakeService IMPORTER = new FakeService("importer");
    private static final FakeService NOTIFICATION = new FakeService("notification");

    @Autowired
    private WebTestClient client;

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        registry.add("CORE_URL", CORE::baseUrl);
        registry.add("IMPORTER_URL", IMPORTER::baseUrl);
        registry.add("NOTIFICATION_URL", NOTIFICATION::baseUrl);
        // нікого не слухає: перевірка відповіді, коли сервіс недоступний
        registry.add("SCHEDULER_URL", () -> "http://127.0.0.1:9");
    }

    @AfterAll
    static void stopServices() {
        CORE.close();
        IMPORTER.close();
        NOTIFICATION.close();
    }

    @Test
    void routesEveryApiPrefixToItsService() {
        assertRoutedTo("/api/import/jobs", "importer");
        assertRoutedTo("/api/notify/bot", "notification");
        assertRoutedTo("/api/documents/orders/0190f0a0-0000-7000-8000-000000000001.png", "notification");
        assertRoutedTo("/api/admin/teachers", "core");
        assertRoutedTo("/api/me/schedule", "core");
        assertRoutedTo("/api/public/meta", "core");
    }

    @Test
    void generatesRequestIdAndForwardsItWithAuthorizationAndHost() {
        EntityExchangeResult<String> result = client.get().uri("/api/admin/teachers")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token-123")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult();

        String requestId = result.getResponseHeaders().getFirst(RequestIdWebFilter.HEADER);
        assertThat(UUID.fromString(requestId)).isNotNull();
        assertThat(result.getResponseBody())
                .contains("\"requestId\":\"" + requestId + "\"")
                .contains("\"authorization\":\"Bearer token-123\"")
                .contains("\"host\":\"localhost:");
    }

    @Test
    void keepsSafeIncomingRequestIdAndReplacesUnsafeOne() {
        client.get().uri("/api/public/meta").header(RequestIdWebFilter.HEADER, "spa-42")
                .exchange()
                .expectHeader().valueEquals(RequestIdWebFilter.HEADER, "spa-42")
                .expectBody(String.class).value(body -> assertThat(body).contains("\"requestId\":\"spa-42\""));

        client.get().uri("/api/public/meta").header(RequestIdWebFilter.HEADER, "forged id <script>")
                .exchange()
                .expectHeader().value(RequestIdWebFilter.HEADER, id -> assertThat(id).hasSize(36));
        client.get().uri("/api/public/meta").header(RequestIdWebFilter.HEADER, "x".repeat(65))
                .exchange()
                .expectHeader().value(RequestIdWebFilter.HEADER, id -> assertThat(id).hasSize(36));
    }

    @Test
    void allowsCorsPreflightFromTheSpaOnly() {
        client.options().uri("/api/admin/teachers")
                .header(HttpHeaders.ORIGIN, "http://localhost:5180")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5180");

        client.options().uri("/api/admin/teachers")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void exposesRequestIdToTheBrowser() {
        client.get().uri("/api/public/meta").header(HttpHeaders.ORIGIN, "http://localhost:8088")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:8088")
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        exposed -> assertThat(exposed).contains("X-Request-Id"));
    }

    @Test
    void streamsServerSentEventsLongerThanTheResponseTimeout() {
        List<String> events = client.mutate().responseTimeout(Duration.ofSeconds(10)).build()
                .get().uri("/api/events/stream").accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(events).containsExactly("{\"n\":1}", "{\"n\":2}");
    }

    @Test
    void unavailableServiceGivesUkrainianProblemDetail() {
        client.get().uri("/api/scheduler/status")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Сервіс тимчасово недоступний")
                .jsonPath("$.requestId").exists();
    }

    @Test
    void unknownPathGivesNotFoundProblemDetail() {
        client.get().uri("/nothing-here")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.title").isEqualTo("Не знайдено");
    }

    @Test
    void healthIsUp() {
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    private void assertRoutedTo(String path, String service) {
        client.get().uri(path).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.service").isEqualTo(service)
                .jsonPath("$.path").isEqualTo(path);
    }
}
