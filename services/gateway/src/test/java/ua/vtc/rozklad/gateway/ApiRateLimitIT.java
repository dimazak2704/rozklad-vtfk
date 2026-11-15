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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Outside {@code /api/public/**} the gateway still brakes one client: anonymous order documents have their
 * own bucket, every other API route a generous one, so a loop over documents or with forged tokens stops
 * at the edge. Each route counts separately, and none of them spends the public bucket.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_RATE_LIMIT_PER_SECOND=1", "API_RATE_LIMIT_BURST=3",
                "DOCUMENTS_RATE_LIMIT_PER_SECOND=1", "DOCUMENTS_RATE_LIMIT_BURST=2"})
@AutoConfigureWebTestClient
@Import(RedisTestcontainersConfiguration.class)
class ApiRateLimitIT {

    private static final FakeService CORE = new FakeService("core");
    private static final FakeService NOTIFICATION = new FakeService("notification");
    private static final FakeService IMPORTER = new FakeService("importer");
    private static final FakeService SCHEDULER = new FakeService("scheduler");

    @Autowired
    private WebTestClient client;

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        registry.add("CORE_URL", CORE::baseUrl);
        registry.add("NOTIFICATION_URL", NOTIFICATION::baseUrl);
        registry.add("IMPORTER_URL", IMPORTER::baseUrl);
        registry.add("SCHEDULER_URL", SCHEDULER::baseUrl);
    }

    @AfterAll
    static void stopServices() {
        CORE.close();
        NOTIFICATION.close();
        IMPORTER.close();
        SCHEDULER.close();
    }

    @Test
    void anonymousDocumentDownloadsAreLimited() {
        assertThat(burst("/api/documents/orders/0190f0a0-0000-7000-8000-000000000001.pdf"))
                .startsWith(200, 200).contains(429);
        // the bucket refills while the test runs (slow CI runners): look at the first refusal itself
        FluxExchangeResult<String> refused = null;
        for (int i = 0; i < 6 && refused == null; i++) {
            FluxExchangeResult<String> result = client.get()
                    .uri("/api/documents/orders/0190f0a0-0000-7000-8000-000000000001.png").exchange()
                    .returnResult(String.class);
            if (result.getStatus().value() == 429) {
                refused = result;
            }
        }
        assertThat(refused).as("the limit refuses a request right after a burst").isNotNull();
        assertThat(refused.getResponseHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(refused.getResponseBody().blockFirst()).contains("urn:rozklad:problem:too-many-requests");
    }

    @Test
    void everyOtherApiRouteHasItsOwnBucket() {
        for (String path : List.of("/api/admin/teachers", "/api/notify/bindings/me", "/api/import/jobs",
                "/api/scheduler/status")) {
            assertThat(burst(path)).as(path).startsWith(200, 200, 200).contains(429);
        }
        // кошики окремі: вичерпаний службовий ліміт не забирає публічний розклад
        client.get().uri("/api/public/meta").exchange().expectStatus().isOk();
    }

    private List<Integer> burst(String path) {
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            statuses.add(client.get().uri(path).exchange().returnResult(String.class).getStatus().value());
        }
        return statuses;
    }
}
