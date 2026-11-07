package ua.vtc.rozklad.core.accounting.internal.web;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.vtc.rozklad.core.accounting.AccountingStatus;
import ua.vtc.rozklad.core.shared.ref.DisciplineRef;
import ua.vtc.rozklad.core.shared.ref.GroupRef;
import ua.vtc.rozklad.core.shared.ref.TeacherRef;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;

@WebMvcTest(AccountingController.class)
@Import(WebSliceTestConfiguration.class)
class AccountingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountingReport report;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void rowsOfAGroup() throws Exception {
        UUID groupId = UUID.randomUUID();
        given(report.rows(null, groupId)).willReturn(List.of(new AccountingRowDto(
                new GroupRef(groupId, "4КІ1", 4), new DisciplineRef(UUID.randomUUID(), "СТП", "СТП", null),
                44, 30, 20, 50, AccountingStatus.WILL_OVERRUN, LocalDate.of(2026, 12, 7),
                new AccountingRowDto.SuggestionDto("DISCIPLINE_FINISHED", "Вичитано 7 грудня: зняти 3 пари після цієї дати",
                        LocalDate.of(2026, 12, 14), 3, List.of(UUID.randomUUID())))));

        mockMvc.perform(get("/api/admin/accounting").param("groupId", groupId.toString()).with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("WILL_OVERRUN"))
                .andExpect(jsonPath("$[0].forecastFinishDate").value("2026-12-07"))
                .andExpect(jsonPath("$[0].suggestion.kind").value("DISCIPLINE_FINISHED"));
    }

    @Test
    void teacherStatisticsOfAMonth() throws Exception {
        given(report.teachers(YearMonth.of(2026, 10))).willReturn(List.of(new TeacherStatsDto(
                new TeacherRef(UUID.randomUUID(), "Прокопчук О.В.", "Прокопчук"), 3, 6, 0)));

        mockMvc.perform(get("/api/admin/accounting/teachers").param("month", "2026-10").with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].substitutionsHours").value(6));
    }

    @Test
    void malformedMonthIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/admin/accounting/teachers").param("month", "жовтень").with(TestJwts.admin()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(report);
    }

    @Test
    void teacherCannotSeeAccounting() throws Exception {
        mockMvc.perform(get("/api/admin/accounting").with(TestJwts.teacher())).andExpect(status().isForbidden());
    }
}
