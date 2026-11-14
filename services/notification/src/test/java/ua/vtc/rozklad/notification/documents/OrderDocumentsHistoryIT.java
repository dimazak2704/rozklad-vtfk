package ua.vtc.rozklad.notification.documents;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.contracts.OrderStatus;
import ua.vtc.rozklad.contracts.events.OrderPayload;
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

    @Test
    void unknownOrderIsStillAPlainNotFound() throws Exception {
        mockMvc.perform(get("/api/documents/orders/" + UUID.randomUUID() + ".docx"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:not-found"));
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
