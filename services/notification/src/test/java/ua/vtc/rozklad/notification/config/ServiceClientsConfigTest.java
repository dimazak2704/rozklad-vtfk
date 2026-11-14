package ua.vtc.rozklad.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import ua.vtc.rozklad.notification.core.CoreDtos.Schedule;
import ua.vtc.rozklad.notification.core.CoreDtos.ScheduleDay;
import ua.vtc.rozklad.notification.core.OwnerType;
import ua.vtc.rozklad.notification.core.RestCoreClient;
import ua.vtc.rozklad.notification.core.UpstreamRejectedException;
import ua.vtc.rozklad.notification.web.RequestIdFilter;

/**
 * Calls to core carry a client-credentials token (fetched once) and the current X-Request-Id. The bot reads a day
 * before core's public history window with that token and other days without it, and reads core's refusal by its
 * ProblemDetail {@code code}.
 */
class ServiceClientsConfigTest {

    private static final UUID GROUP = UUID.fromString("1a2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d");
    private static final LocalDate SEPTEMBER = LocalDate.of(2026, 9, 7);

    private final ServiceClientsConfig config = new ServiceClientsConfig();
    private final List<String> tokenRequests = new CopyOnWriteArrayList<>();
    private final Map<String, String> coreHeaders = new ConcurrentHashMap<>();
    private final List<String> scheduleRequests = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @BeforeEach
    void startFakeAuthAndCore() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            tokenRequests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "application/json",
                    "{\"access_token\":\"service-token\",\"token_type\":\"Bearer\",\"expires_in\":300}");
        });
        server.createContext("/api/internal/catalog/snapshot", exchange -> {
            coreHeaders.put("authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            coreHeaders.put("requestId", String.valueOf(exchange.getRequestHeaders().getFirst("X-Request-Id")));
            respond(exchange, 200, "application/json", "{}");
        });
        // як core: день до публічного вікна бачить лише токен персоналу або бота
        server.createContext("/api/public/schedule/group/" + GROUP, exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            scheduleRequests.add(exchange.getRequestURI().getQuery() + " " + authorization);
            if ("Bearer service-token".equals(authorization)) {
                respond(exchange, 200, "application/json", """
                        {"owner":{"type":"GROUP","id":"%s","label":"4КІ1"},"from":"2026-09-07","to":"2026-09-07",
                         "days":[{"date":"2026-09-07","weekday":1,"weekdayLabel":"Понеділок","parity":"NUMERATOR",
                                  "studyDay":true,"note":null,"lessons":[],"practices":[]}]}""".formatted(GROUP));
            } else {
                respond(exchange, 404, "application/problem+json", """
                        {"type":"urn:rozklad:problem:history-not-public","title":"Архів недоступний","status":404,
                         "detail":"Розклад із замінами за тижні до 12.10.2026 бачить лише персонал коледжу.",
                         "instance":"/api/public/schedule/group/%s","code":"HISTORY_NOT_PUBLIC",
                         "publicHistoryFrom":"2026-10-12","requestId":"r-1"}""".formatted(GROUP));
            }
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
        RestClient core = coreRestClient(base());

        MDC.put(RequestIdFilter.MDC_KEY, "req-77");
        core.get().uri("/api/internal/catalog/snapshot").retrieve().toBodilessEntity();
        core.get().uri("/api/internal/catalog/snapshot").retrieve().toBodilessEntity();

        assertThat(coreHeaders).containsEntry("authorization", "Bearer service-token")
                .containsEntry("requestId", "req-77");
        assertThat(tokenRequests).hasSize(1);
        assertThat(tokenRequests.getFirst()).contains("grant_type=client_credentials");
    }

    @Test
    void archiveDaysGoWithTheServiceTokenAndOtherDaysWithout() {
        RestCoreClient client = new RestCoreClient(coreRestClient(base()), RestClient.builder(), urls(base()));

        Schedule archived = client.archiveSchedule(OwnerType.GROUP, GROUP, SEPTEMBER, SEPTEMBER);

        assertThat(archived.days()).extracting(ScheduleDay::date).containsExactly(SEPTEMBER);
        assertThatThrownBy(() -> client.schedule(OwnerType.GROUP, GROUP, SEPTEMBER, SEPTEMBER))
                .isInstanceOfSatisfying(UpstreamRejectedException.class, refused -> {
                    assertThat(refused.isHistoryNotPublic()).as("core's code read from the ProblemDetail").isTrue();
                    assertThat(refused.detail())
                            .isEqualTo("Розклад із замінами за тижні до 12.10.2026 бачить лише персонал коледжу.");
                });
        assertThat(scheduleRequests).containsExactly("from=2026-09-07&to=2026-09-07 Bearer service-token",
                "from=2026-09-07&to=2026-09-07 null");
    }

    private RestClient coreRestClient(String base) {
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
        return config.coreRestClient(builder, manager, urls(base));
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static ServiceUrlsProperties urls(String base) {
        return new ServiceUrlsProperties(URI.create(base), URI.create(base), URI.create("http://localhost:5180"));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
