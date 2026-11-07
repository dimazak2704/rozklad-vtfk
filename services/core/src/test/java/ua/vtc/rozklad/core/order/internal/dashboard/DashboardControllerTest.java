package ua.vtc.rozklad.core.order.internal.dashboard;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;

@WebMvcTest(DashboardController.class)
@Import(WebSliceTestConfiguration.class)
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private Dashboard dashboard;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void dispatcherSeesTheFiguresOfTheDate() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 19);
        given(dashboard.of(date)).willReturn(new DashboardDto(date, Parity.DENOMINATOR, 2, 3, 1, 4, 1, null, null,
                null, List.of(new DashboardDto.Alert("UNCOVERED_LESSONS", DashboardDto.Severity.WARN,
                "3 пари без рішення через відсутність викладачів", "/admin/changes?date=2026-10-19"))));

        mockMvc.perform(get("/api/admin/dashboard").param("date", "2026-10-19").with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uncoveredLessons").value(3))
                .andExpect(jsonPath("$.alerts[0].severity").value("WARN"))
                .andExpect(jsonPath("$.alerts[0].link").value("/admin/changes?date=2026-10-19"));
    }

    @Test
    void teacherHasNoDashboard() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard").with(TestJwts.teacher())).andExpect(status().isForbidden());
    }
}
