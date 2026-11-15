package ua.vtc.rozklad.core.settings.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.vtc.rozklad.core.settings.SettingsService;
import ua.vtc.rozklad.core.settings.SystemSettings;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;

/** {@code GET/PUT /api/admin/settings}. */
@WebMvcTest(AdminSettingsController.class)
@Import(WebSliceTestConfiguration.class)
class AdminSettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private SettingsService settings;

    @Test
    void adminReadsTypedSettings() throws Exception {
        given(settings.current()).willReturn(SystemSettings.DEFAULTS);
        given(settings.version()).willReturn(3L);

        mockMvc.perform(get("/api/admin/settings").with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.approvalMode").value("NOTIFY"))
                .andExpect(jsonPath("$.confirmTimeoutMinutes").value(120))
                .andExpect(jsonPath("$.signatoryPosition").value("Заступник директора з НР"))
                .andExpect(jsonPath("$.signatoryName").value("Микола ПОЛІЩУК"))
                .andExpect(jsonPath("$.hoursPerPair").value(2))
                .andExpect(jsonPath("$.showTeacherFullNames").value(false))
                .andExpect(jsonPath("$.changesHistoryDays").value(7))
                .andExpect(jsonPath("$.teacherMaxPairsPerDay").value(4));
    }

    @Test
    void putSavesWithTheAdminAsActor() throws Exception {
        given(settings.current()).willReturn(SystemSettings.DEFAULTS);
        given(settings.update(any(), eq(4L), eq("admin"))).willAnswer(call -> call.getArgument(0));
        given(settings.version()).willReturn(5L);

        mockMvc.perform(put("/api/admin/settings").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvalMode":"AUTO","confirmTimeoutMinutes":60,"signatoryPosition":"Директор",
                                 "signatoryName":"Світлана ВАСИЛЮК","collegeName":"ВТФК","hoursPerPair":2,
                                 "showTeacherFullNames":true,"activeBellProfileId":null,"version":4}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalMode").value("AUTO"))
                .andExpect(jsonPath("$.version").value(5));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 365})
    void historyDaysAtTheBoundsAreSaved(int days) throws Exception {
        given(settings.current()).willReturn(SystemSettings.DEFAULTS);
        given(settings.update(any(), eq(4L), eq("admin"))).willAnswer(call -> call.getArgument(0));
        given(settings.version()).willReturn(5L);

        mockMvc.perform(put("/api/admin/settings").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withHistoryDays(String.valueOf(days))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changesHistoryDays").value(days));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 366})
    void historyDaysOutsideZeroTo365Are400(int days) throws Exception {
        mockMvc.perform(put("/api/admin/settings").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withHistoryDays(String.valueOf(days))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("changesHistoryDays"))
                .andExpect(jsonPath("$.errors[0].message").value("Від 0 до 365 днів"));
        verify(settings, never()).update(any(), anyLong(), any());
    }

    /** An older client does not know the field: its save keeps the stored value instead of resetting it. */
    @Test
    void putWithoutHistoryDaysKeepsTheStoredValue() throws Exception {
        SystemSettings stored = new SystemSettings(SystemSettings.DEFAULTS.approvalMode(), 120, "Директор", "Ім'я",
                "ВТФК", 2, false, 30, null, 6);
        given(settings.current()).willReturn(stored);
        given(settings.update(any(), eq(4L), eq("admin"))).willAnswer(call -> call.getArgument(0));
        given(settings.version()).willReturn(5L);

        mockMvc.perform(put("/api/admin/settings").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withHistoryDays(null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changesHistoryDays").value(30));
        ArgumentCaptor<SystemSettings> saved = ArgumentCaptor.forClass(SystemSettings.class);
        verify(settings).update(saved.capture(), eq(4L), eq("admin"));
        assertThat(saved.getValue().changesHistoryDays()).isEqualTo(30);
        assertThat(saved.getValue().teacherMaxPairsPerDay()).as("an older client keeps the daily limit too")
                .isEqualTo(6);
    }

    /** genfix: the teacher's daily pair limit of the generator, 1..8. */
    @ParameterizedTest
    @ValueSource(ints = {0, 9})
    void teacherDailyLimitOutsideOneToEightIs400(int pairs) throws Exception {
        mockMvc.perform(put("/api/admin/settings").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withHistoryDays("7,\"teacherMaxPairsPerDay\":" + pairs)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("teacherMaxPairsPerDay"))
                .andExpect(jsonPath("$.errors[0].message").value("Від 1 до 8 пар"));
        verify(settings, never()).update(any(), anyLong(), any());
    }

    @Test
    void teacherDailyLimitIsSaved() throws Exception {
        given(settings.current()).willReturn(SystemSettings.DEFAULTS);
        given(settings.update(any(), eq(4L), eq("admin"))).willAnswer(call -> call.getArgument(0));
        given(settings.version()).willReturn(5L);

        mockMvc.perform(put("/api/admin/settings").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withHistoryDays("7,\"teacherMaxPairsPerDay\":5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teacherMaxPairsPerDay").value(5));
    }

    @Test
    void putWithoutVersionIs400() throws Exception {
        mockMvc.perform(put("/api/admin/settings").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvalMode":"AUTO","confirmTimeoutMinutes":60,"signatoryPosition":"Директор",
                                 "signatoryName":"Світлана ВАСИЛЮК","collegeName":"ВТФК","hoursPerPair":2,
                                 "showTeacherFullNames":true,"activeBellProfileId":null}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"));
    }

    @Test
    void outOfRangeValuesAre400() throws Exception {
        mockMvc.perform(put("/api/admin/settings").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvalMode":"AUTO","confirmTimeoutMinutes":2,"signatoryPosition":"",
                                 "signatoryName":"Ім'я","collegeName":"ВТФК","hoursPerPair":7,
                                 "showTeacherFullNames":false,"version":0}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("confirmTimeoutMinutes"))
                .andExpect(jsonPath("$.errors[0].message").value("Від 5 до 1440 хвилин"))
                .andExpect(jsonPath("$.errors[1].field").value("hoursPerPair"))
                .andExpect(jsonPath("$.errors[1].message").value("Від 1 до 4 годин"))
                .andExpect(jsonPath("$.errors[2].field").value("signatoryPosition"))
                .andExpect(jsonPath("$.errors[2].message").value("Вкажіть посаду підписанта"));
    }

    @Test
    void teacherCannotSeeSettings() throws Exception {
        mockMvc.perform(get("/api/admin/settings").with(TestJwts.teacher())).andExpect(status().isForbidden());
    }

    /** A valid PUT body; {@code days} {@code null} leaves the field out. */
    private static String withHistoryDays(String days) {
        return """
                {"approvalMode":"AUTO","confirmTimeoutMinutes":60,"signatoryPosition":"Директор",
                 "signatoryName":"Світлана ВАСИЛЮК","collegeName":"ВТФК","hoursPerPair":2,
                 "showTeacherFullNames":true,%s"activeBellProfileId":null,"version":4}"""
                .formatted(days == null ? "" : "\"changesHistoryDays\":" + days + ",");
    }
}
