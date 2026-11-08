package ua.vtc.rozklad.core.timetable.web;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.ErrorResponseException;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.identity.CurrentUserService;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;
import ua.vtc.rozklad.core.timetable.ScheduleOwnerType;
import ua.vtc.rozklad.core.timetable.ScheduleQueries;
import ua.vtc.rozklad.core.timetable.ScheduleView;

@WebMvcTest(MyScheduleController.class)
@Import(WebSliceTestConfiguration.class)
class MyScheduleControllerTest {

    private static final UUID KRENTSIN = UUID.fromString("0190f0a0-0000-7000-8000-000000000401");
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private ScheduleQueries schedules;

    @MockitoBean
    private CurrentUserService currentUser;

    @Test
    void teacherGetsOwnSchedule() throws Exception {
        given(currentUser.requireTeacherId()).willReturn(KRENTSIN);
        given(schedules.schedule(ScheduleOwnerType.TEACHER, KRENTSIN, MONDAY, MONDAY.plusDays(4)))
                .willReturn(new ScheduleView(new ScheduleView.Owner(ScheduleOwnerType.TEACHER, KRENTSIN, "Кренцін М.Д."),
                        MONDAY, MONDAY.plusDays(4),
                        List.of(new ScheduleView.Day(MONDAY, 1, "ПОНЕДІЛОК", Parity.DENOMINATOR, true, null, List.of()))));

        mockMvc.perform(get("/api/me/schedule").with(TestJwts.teacher())
                        .param("from", "2026-10-19").param("to", "2026-10-23"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner.type").value("TEACHER"))
                .andExpect(jsonPath("$.owner.label").value("Кренцін М.Д."))
                .andExpect(jsonPath("$.days[0].parity").value("DENOMINATOR"));
    }

    @Test
    void accountWithoutATeacherIsForbidden() throws Exception {
        given(currentUser.requireTeacherId()).willThrow(new ErrorResponseException(HttpStatus.FORBIDDEN));

        mockMvc.perform(get("/api/me/schedule").with(TestJwts.admin())).andExpect(status().isForbidden());
        verifyNoInteractions(schedules);
    }

    @Test
    void anonymousCallerIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/me/schedule")).andExpect(status().isUnauthorized());
        verifyNoInteractions(schedules);
    }

    @Test
    void rangeLongerThan31DaysIsRejected() throws Exception {
        mockMvc.perform(get("/api/me/schedule").with(TestJwts.teacher())
                        .param("from", "2026-10-01").param("to", "2026-11-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("to"));
        verifyNoInteractions(schedules);
    }
}
