package ua.vtc.rozklad.core.order.internal.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.vtc.rozklad.contracts.OrderKind;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.contracts.OrderStatus;
import ua.vtc.rozklad.core.order.internal.draft.OrderDrafts;
import ua.vtc.rozklad.core.order.internal.publish.OrderPublication;
import ua.vtc.rozklad.core.order.internal.view.OrderDto;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.web.PageResponse;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;

/** {@code /api/admin/orders}: roles, validation and the published order. */
@WebMvcTest(AdminOrderController.class)
@Import(WebSliceTestConfiguration.class)
class AdminOrderControllerTest {

    private static final UUID ORDER_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderQueries queries;

    @MockitoBean
    private OrderDrafts drafts;

    @MockitoBean
    private OrderPublication publication;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void draftOfADateWithTheCollegeHeader() throws Exception {
        given(drafts.draftFor(LocalDate.of(2026, 10, 19))).willReturn(order(OrderStatus.DRAFT, null, "РОЗПОРЯДЖЕННЯ"));

        mockMvc.perform(get("/api/admin/orders/draft").param("date", "2026-10-19").with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("РОЗПОРЯДЖЕННЯ"))
                .andExpect(jsonPath("$.subtitle").value("По коледжу про деякі зміни в розкладі занять"))
                .andExpect(jsonPath("$.dateLabel").value("На 19 жовтня 2026 р."))
                .andExpect(jsonPath("$.weekdayLabel").value("ПОНЕДІЛОК"))
                .andExpect(jsonPath("$.parityLabel").value("ЗНАМЕННИК"))
                .andExpect(jsonPath("$.items[0].rowType").value("REPLACEMENT"))
                .andExpect(jsonPath("$.roomChanges").isEmpty());
    }

    @Test
    void draftNeedsADate() throws Exception {
        mockMvc.perform(get("/api/admin/orders/draft").with(TestJwts.admin())).andExpect(status().isBadRequest());
        verifyNoInteractions(drafts);
    }

    @Test
    void publishedOrderGetsItsNumber() throws Exception {
        given(publication.publish(ORDER_ID, "admin")).willReturn(order(OrderStatus.PUBLISHED, 35,
                "РОЗПОРЯДЖЕННЯ №35"));

        mockMvc.perform(post("/api/admin/orders/{id}/publish", ORDER_ID).with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(35))
                .andExpect(jsonPath("$.title").value("РОЗПОРЯДЖЕННЯ №35"));
    }

    @Test
    void staleDraftIsAConflict() throws Exception {
        given(publication.publish(ORDER_ID, "admin")).willThrow(new ConflictException("Чернетка застаріла"));

        mockMvc.perform(post("/api/admin/orders/{id}/publish", ORDER_ID).with(TestJwts.admin()))
                .andExpect(status().isConflict());
    }

    @Test
    void publishingTwiceIs422() throws Exception {
        given(publication.publish(ORDER_ID, "admin"))
                .willThrow(new BusinessRuleException("ORDER_ALREADY_PUBLISHED", "Розпорядження вже опубліковане."));

        mockMvc.perform(post("/api/admin/orders/{id}/publish", ORDER_ID).with(TestJwts.admin()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_PUBLISHED"));
    }

    @Test
    void notesMustNotBeBlank() throws Exception {
        mockMvc.perform(put("/api/admin/orders/{id}/notes", ORDER_ID).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":[\" \"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("notes[0]"));
        verifyNoInteractions(drafts);
    }

    @Test
    void listPagesAreLimited() throws Exception {
        mockMvc.perform(get("/api/admin/orders").param("size", "101").with(TestJwts.admin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void teacherCannotSeeDrafts() throws Exception {
        mockMvc.perform(get("/api/admin/orders/draft").param("date", "2026-10-19").with(TestJwts.teacher()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(drafts);
    }

    @Test
    void listReturnsAPage() throws Exception {
        given(queries.list(any(), any(), any(), any(), anyInt(), anyInt())).willReturn(new PageResponse<>(
                List.of(new OrderDto.Summary(ORDER_ID, 35, LocalDate.of(2026, 10, 19), OrderKind.ORDER,
                        OrderStatus.PUBLISHED, 8, Instant.parse("2026-10-18T14:00:00Z"))), 0, 20, 1));

        mockMvc.perform(get("/api/admin/orders").param("q", "35").with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].number").value(35))
                .andExpect(jsonPath("$.items[0].itemCount").value(8))
                .andExpect(jsonPath("$.total").value(1));
    }

    private static OrderDto order(OrderStatus status, Integer number, String title) {
        OrderDto.Item row = new OrderDto.Item(UUID.randomUUID(), "4КІ1", "2", OrderRowType.REPLACEMENT, "СТП", "Кренцін",
                "Зв.зам.", "НДКС", "Непийвода", "6", UUID.randomUUID());
        return new OrderDto(ORDER_ID, number, LocalDate.of(2026, 10, 19), OrderKind.ORDER, null, status, title,
                "ПОНЕДІЛОК", "ЗНАМЕННИК", List.of(row), List.of(), List.of(), "Заступник директора з НР",
                "Микола ПОЛІЩУК", "По коледжу про деякі зміни в розкладі занять", "На 19 жовтня 2026 р.",
                status == OrderStatus.PUBLISHED ? Instant.parse("2026-10-18T14:00:00Z") : null,
                status == OrderStatus.PUBLISHED ? "admin" : null, 1);
    }
}
