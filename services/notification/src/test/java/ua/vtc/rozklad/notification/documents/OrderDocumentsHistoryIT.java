package ua.vtc.rozklad.notification.documents;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.contracts.OrderStatus;
import ua.vtc.rozklad.contracts.events.OrderPayload;
import ua.vtc.rozklad.notification.config.TokenRoles;
import ua.vtc.rozklad.notification.support.IntegrationTest;
import ua.vtc.rozklad.notification.support.Order35;

/**
 * Order documents and core's public history window: today is Monday 19.10.2026 and visitors see a week back
 * (from 12.10); signed-in staff open every published order. Answers vary by {@code Authorization}.
 */
class OrderDocumentsHistoryIT extends IntegrationTest {

    @Autowired
    private PublishedOrders publishedOrders;

    @Test
    void visitorOpensAnOrderOfTheFirstDayOfTheWindowButNotTheDayBefore() throws Exception {
        UUID firstDay = publish(30, LocalDate.of(2026, 10, 12));
        UUID dayBefore = publish(29, LocalDate.of(2026, 10, 9));

        mockMvc.perform(get("/api/documents/orders/" + firstDay + ".docx"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"))
                .andExpect(header().string(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION));
        mockMvc.perform(get("/api/documents/orders/" + dayBefore + ".docx"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:history-not-public"))
                .andExpect(jsonPath("$.code").value("HISTORY_NOT_PUBLIC"))
                .andExpect(jsonPath("$.publicHistoryFrom").value("2026-10-12"))
                .andExpect(jsonPath("$.detail").value("Розпорядження за дні до 12.10.2026 бачить лише персонал коледжу."));
    }

    @Test
    void staffOpenOldOrdersAsAPrivateAnswer() throws Exception {
        UUID september = publish(5, LocalDate.of(2026, 9, 7));

        mockMvc.perform(get("/api/documents/orders/" + september + ".docx").with(teacher("teacher-token")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, private"))
                .andExpect(header().string(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION));
        mockMvc.perform(get("/api/documents/orders/" + september + ".docx").with(admin()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/documents/orders/" + september + ".docx"))
                .andExpect(status().isNotFound());
    }

    /** Today's order needs no window; an older one cannot be checked while core is down and is not served. */
    @Test
    void withCoreDownTodaysOrderIsServedAndAnOlderOneIsAnHonest503() throws Exception {
        UUID today = publish(35, Order35.DATE);
        UUID older = publish(34, LocalDate.of(2026, 10, 16));
        core.setUnavailable(true);

        mockMvc.perform(get("/api/documents/orders/" + today + ".docx")).andExpect(status().isOk());
        mockMvc.perform(get("/api/documents/orders/" + older + ".docx"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:service-unavailable"));
        mockMvc.perform(get("/api/documents/orders/" + older + ".docx").with(admin())).andExpect(status().isOk());
    }

    /**
     * Wednesday 21.10: visitors see orders from 14.10 on. Documents follow the day rule; the whole weeks of schedules
     * (and of the bot) do not apply, so Monday 12.10 and Tuesday 13.10 stay closed.
     */
    @Test
    void midWeekBoundClosesTheDaysBeforeItOfItsOwnWeek() throws Exception {
        clock.set(Instant.parse("2026-10-21T07:00:00Z"));
        core.setToday(LocalDate.of(2026, 10, 21));
        UUID monday = publish(31, LocalDate.of(2026, 10, 12));
        UUID tuesday = publish(32, LocalDate.of(2026, 10, 13));
        UUID wednesday = publish(33, LocalDate.of(2026, 10, 14));

        for (UUID closed : List.of(monday, tuesday)) {
            mockMvc.perform(get("/api/documents/orders/" + closed + ".docx"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("HISTORY_NOT_PUBLIC"))
                    .andExpect(jsonPath("$.publicHistoryFrom").value("2026-10-14"));
        }
        mockMvc.perform(get("/api/documents/orders/" + wednesday + ".docx")).andExpect(status().isOk());
    }

    /** Service tokens are not staff here: the importer's or a bare SERVICE token sees what a visitor sees. */
    @Test
    void serviceTokensAreVisitors() throws Exception {
        UUID september = publish(5, LocalDate.of(2026, 9, 7));

        mockMvc.perform(get("/api/documents/orders/" + september + ".docx")
                        .with(roles("service-account-rozklad-importer", "SERVICE", "SERVICE_IMPORTER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:history-not-public"));
        mockMvc.perform(get("/api/documents/orders/" + september + ".docx").with(roles("some-service", "SERVICE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:history-not-public"));
    }

    /**
     * A token the service cannot verify (malformed, or unsigned with an ADMIN role and a past expiry) is 401, so the
     * SPA drops it — never trusted as staff, never quietly read as a visitor.
     */
    @Test
    void tokenTheServiceCannotVerifyIs401() throws Exception {
        UUID september = publish(5, LocalDate.of(2026, 9, 7));
        UUID today = publish(35, Order35.DATE);
        String unsigned = base64Url("{\"alg\":\"none\"}") + "." + base64Url(
                "{\"sub\":\"admin\",\"roles\":[\"ADMIN\"],\"exp\":1760000000}") + ".";

        for (String bearer : List.of("not-a-jwt", unsigned)) {
            for (UUID order : List.of(september, today)) {
                mockMvc.perform(get("/api/documents/orders/" + order + ".docx")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.type").value("urn:rozklad:problem:unauthorized"));
            }
        }
    }

    @Test
    void unknownOrderIsStillAPlainNotFound() throws Exception {
        mockMvc.perform(get("/api/documents/orders/" + UUID.randomUUID() + ".docx"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:not-found"));
    }

    private static JwtRequestPostProcessor roles(String username, String... roles) {
        return jwt().jwt(token -> token.claim("roles", List.of(roles)).claim("preferred_username", username))
                .authorities(TokenRoles::authorities);
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private UUID publish(int number, LocalDate date) {
        UUID id = UUID.randomUUID();
        publishedOrders.store(new OrderPayload(id, number, date, OrderKind.ORDER, null, OrderStatus.PUBLISHED, null,
                "ПОНЕДІЛОК", "ЗНАМЕННИК", List.of(Order35.row("4КІ1", "2", OrderRowType.REPLACEMENT, "СТП", "Кренцін",
                        "Відп.", "НДКС", "Непийвода", "6", null)), List.of(), List.of(), "Заступник директора з НР",
                "Микола ПОЛІЩУК", "I семестр 2026/2027", List.of(), List.of()), UUID.randomUUID());
        return id;
    }
}
