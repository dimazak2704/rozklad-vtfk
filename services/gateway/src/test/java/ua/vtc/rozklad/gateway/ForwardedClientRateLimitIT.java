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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Production runs the gateway behind Caddy with {@code SERVER_FORWARD_HEADERS_STRATEGY=native}: Caddy sets
 * {@code X-Forwarded-For} to the browser's address and strips a client's {@code Forwarded}. The public limit
 * must then be per browser, not one bucket for everything that comes through Caddy.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"PUBLIC_RATE_LIMIT_PER_SECOND=1", "PUBLIC_RATE_LIMIT_BURST=3",
                "server.forward-headers-strategy=native"})
@AutoConfigureWebTestClient
@Import(RedisTestcontainersConfiguration.class)
class ForwardedClientRateLimitIT {

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
    void eachForwardedClientHasItsOwnBucket() {
        assertThat(burst("X-Forwarded-For", "203.0.113.10")).as("one browser over the limit").contains(429);

        // інший браузер через того самого Caddy (той самий сокет) має свій ліміт
        assertThat(burst("X-Forwarded-For", "203.0.113.11")).startsWith(200, 200, 200);
    }

    /**
     * Reactor Netty prefers RFC 7239 {@code Forwarded} over {@code X-Forwarded-For}: a client's own
     * {@code Forwarded} would choose its bucket. This is why the Caddyfile strips it (strip_client_forwarding);
     * if an upgrade changes the precedence, this test tells.
     */
    @Test
    void forwardedHeaderWinsOverXForwardedForSoCaddyMustStripIt() {
        assertThat(burst("X-Forwarded-For", "203.0.113.20")).contains(429);

        List<Integer> spoofed = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            spoofed.add(client.get().uri("/api/public/meta")
                    .header("X-Forwarded-For", "203.0.113.20")
                    .header("Forwarded", "for=198.51.100.7")
                    .exchange().returnResult(String.class).getStatus().value());
        }
        assertThat(spoofed).containsOnly(200);
    }

    private List<Integer> burst(String header, String value) {
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            statuses.add(client.get().uri("/api/public/meta").header(header, value).exchange()
                    .returnResult(String.class).getStatus().value());
        }
        return statuses;
    }
}
