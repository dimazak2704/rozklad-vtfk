package ua.vtc.rozklad.core.order.internal.publicview;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.vtc.rozklad.contracts.OrderRowType;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;

@WebMvcTest(PublicChangesController.class)
@Import(WebSliceTestConfiguration.class)
class PublicChangesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicChanges changes;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void anyoneSeesThePublishedChangesOfADate() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 19);
        given(changes.of(date)).willReturn(new PublicChangesDto(date, Parity.DENOMINATOR, true, "Понеділок",
                new PublicChangesDto.PublishedOrder(UUID.randomUUID(), 35, Instant.parse("2026-10-18T14:00:00Z"), false),
                List.of(new PublicChangesDto.PublishedOrder(UUID.randomUUID(), 36, Instant.parse("2026-10-19T07:00:00Z"),
                        false)),
                List.of(new PublicChangesDto.GroupChanges(new PublicChangesDto.ChangeGroup(UUID.randomUUID(), "1ТК1", 1),
                        List.of(new PublicChangesDto.ChangeView("1", OrderRowType.CANCELLATION,
                                new PublicChangesDto.Side("Ін. мова", "Крисак Л. (п.)"), "Відп.",
                                new PublicChangesDto.Side("Пари не буде", null), null, "1 підгрупа", 36))))));

        mockMvc.perform(get("/api/public/changes").param("date", "2026-10-19"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parity").value("DENOMINATOR"))
                .andExpect(jsonPath("$.studyDay").value(true))
                .andExpect(jsonPath("$.order.number").value(35))
                .andExpect(jsonPath("$.groups[0].group.code").value("1ТК1"))
                .andExpect(jsonPath("$.groups[0].items[0].to.discipline").value("Пари не буде"))
                .andExpect(jsonPath("$.groups[0].items[0].subgroupNote").value("1 підгрупа"))
                .andExpect(jsonPath("$.amendments[0].number").value(36))
                .andExpect(jsonPath("$.groups[0].items[0].orderNumber").value(36));
    }

    @Test
    void malformedDateIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/public/changes").param("date", "19.10.2026")).andExpect(status().isBadRequest());
    }
}
