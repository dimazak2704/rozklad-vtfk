package ua.vtc.rozklad.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The public API is limited per client IP with a Redis token bucket; other routes have their own buckets
 * ({@code ApiRateLimitIT}) and do not spend this one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"PUBLIC_RATE_LIMIT_PER_SECOND=1", "PUBLIC_RATE_LIMIT_BURST=3"})
@AutoConfigureWebTestClient
@Import(RedisTestcontainersConfiguration.class)
class PublicRateLimitIT {

    private static final FakeService CORE = new FakeService("core");

    @Autowired
    private WebTestClient client;

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        registry.add("CORE_URL", CORE::baseUrl);
    }

    @AfterAll
    static void stopServices() {
        CORE.close();
    }

    @Test
    void burstAbovePublicLimitIsRejectedWith429() {
        List<Integer> statuses = new ArrayList<>();
        FluxExchangeResult<String> refused = null;
        for (int i = 0; i < 8; i++) {
            FluxExchangeResult<String> result = client.get().uri("/api/public/meta").exchange()
                    .returnResult(String.class);
            statuses.add(result.getStatus().value());
            if (refused == null && result.getStatus().value() == 429) {
                refused = result;
            }
        }

        assertThat(statuses).startsWith(200, 200, 200).contains(429);
        // the bucket refills while the test runs (slow CI runners): look at the first refusal itself.
        // A limiter refusal is the same ProblemDetail as other errors, with a hint when to retry.
        assertThat(refused).isNotNull();
        HttpHeaders headers = refused.getResponseHeaders();
        assertThat(headers.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(headers.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(headers.containsHeader("X-Request-Id")).isTrue();
        assertThat(refused.getResponseBody().blockFirst())
                .contains("urn:rozklad:problem:too-many-requests")
                .contains("429")
                .contains("Забагато запитів")
                .contains("requestId");
    }

    @Test
    void authenticatedApiIsNotLimitedByThePublicBucket() {
        for (int i = 0; i < 8; i++) {
            client.get().uri("/api/admin/teachers").exchange().expectStatus().isOk();
        }
    }
}
