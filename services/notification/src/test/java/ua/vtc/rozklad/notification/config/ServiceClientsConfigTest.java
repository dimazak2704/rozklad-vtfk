package ua.vtc.rozklad.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.web.client.RestClient;
import ua.vtc.rozklad.notification.web.RequestIdFilter;

/** Calls to core carry a client-credentials token (fetched once) and the current X-Request-Id. */
class ServiceClientsConfigTest {

    private final ServiceClientsConfig config = new ServiceClientsConfig();
    private final List<String> tokenRequests = new CopyOnWriteArrayList<>();
    private final Map<String, String> coreHeaders = new ConcurrentHashMap<>();
    private HttpServer server;

    @BeforeEach
    void startFakeKeycloakAndCore() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            tokenRequests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"access_token\":\"service-token\",\"token_type\":\"Bearer\",\"expires_in\":300}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/internal/catalog/snapshot", exchange -> {
            coreHeaders.put("authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            coreHeaders.put("requestId", String.valueOf(exchange.getRequestHeaders().getFirst("X-Request-Id")));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        MDC.clear();
    }

    @Test
    void coreClientSendsServiceTokenAndRequestId() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        ClientRegistration registration = ClientRegistration.withRegistrationId(ServiceClientsConfig.REGISTRATION_ID)
                .clientId("rozklad-notification")
                .clientSecret("dev-notification-secret")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenUri(base + "/token")
                .build();
        var registrations = new InMemoryClientRegistrationRepository(registration);
        OAuth2AuthorizedClientManager manager = config.serviceAuthorizedClientManager(registrations,
                new InMemoryOAuth2AuthorizedClientService(registrations));
        RestClient.Builder builder = RestClient.builder();
        config.requestIdRestClientCustomizer().customize(builder);
        RestClient core = config.coreRestClient(builder, manager,
                new ServiceUrlsProperties(URI.create(base), URI.create(base), URI.create("http://localhost:5180")));

        MDC.put(RequestIdFilter.MDC_KEY, "req-77");
        core.get().uri("/api/internal/catalog/snapshot").retrieve().toBodilessEntity();
        core.get().uri("/api/internal/catalog/snapshot").retrieve().toBodilessEntity();

        assertThat(coreHeaders).containsEntry("authorization", "Bearer service-token")
                .containsEntry("requestId", "req-77");
        assertThat(tokenRequests).hasSize(1);
        assertThat(tokenRequests.getFirst()).contains("grant_type=client_credentials");
    }
}
