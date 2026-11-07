package ua.vtc.rozklad.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import ua.vtc.rozklad.contracts.events.RozkladEvents;
import ua.vtc.rozklad.notification.config.KeycloakRealmRoleConverter;
import ua.vtc.rozklad.notification.config.TelegramProperties;
import ua.vtc.rozklad.notification.support.TestcontainersConfiguration;

/** Smoke test: notification boots against real PostgreSQL, RabbitMQ and Redis with its security rules. */
@SpringBootTest(properties = {"app.telegram.bot-token=", "app.telegram.owner-chat-id="})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NotificationApplicationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private TelegramProperties telegram;

    @Test
    void healthIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void notifyApiIsForAdmins() throws Exception {
        mockMvc.perform(get("/api/notify/bindings"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(jsonPath("$.title").value("Потрібна автентифікація"));
        mockMvc.perform(get("/api/notify/bindings").with(token("TEACHER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/documents/orders/render").with(token("TEACHER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void publishedOrderDocumentsAndTeacherBindingCodesAreReachable() throws Exception {
        // ендпоінтів ще немає: будь-яка відповідь, крім 401/403, означає, що правила доступу пропускають запит
        mockMvc.perform(get("/api/documents/orders/0190f0a0-0000-7000-8000-000000000001.png"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
        mockMvc.perform(post("/api/notify/bindings/codes").with(token("TEACHER")))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
    }

    @Test
    void eventsQueueIsDeclaredAndBotIsOffWithoutToken() {
        assertThat(amqpAdmin.getQueueInfo(RozkladEvents.Queues.NOTIFICATION_EVENTS)).isNotNull();
        assertThat(amqpAdmin.getQueueInfo(RozkladEvents.Queues.deadLetterQueue(
                RozkladEvents.Queues.NOTIFICATION_EVENTS))).isNotNull();
        assertThat(telegram.enabled()).isFalse();
        assertThat(telegram.toString()).doesNotContain(":AA");
    }

    private static JwtRequestPostProcessor token(String role) {
        return jwt().jwt(token -> token.claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(new KeycloakRealmRoleConverter());
    }
}
