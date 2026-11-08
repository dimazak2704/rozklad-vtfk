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
import org.springframework.test.web.reactive.server.WebTestClient;

/** The public API is limited per client IP with a Redis token bucket; other routes are not. */
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
        for (int i = 0; i < 8; i++) {
            statuses.add(client.get().uri("/api/public/meta").exchange().returnResult(String.class)
                    .getStatus().value());
        }

        assertThat(statuses).startsWith(200, 200, 200).contains(429);
        // відмова лімітера — той самий ProblemDetail, що й інші помилки, і підказка, коли повторити
        client.get().uri("/api/public/meta").exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "1")
                .expectHeader().exists("X-Request-Id")
                .expectBody()
                .jsonPath("$.type").isEqualTo("urn:rozklad:problem:too-many-requests")
                .jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.title").isEqualTo("Забагато запитів")
                .jsonPath("$.requestId").exists();
    }

    @Test
    void authenticatedApiIsNotRateLimitedByTheGateway() {
        for (int i = 0; i < 8; i++) {
            client.get().uri("/api/admin/teachers").exchange().expectStatus().isOk();
        }
    }
}
