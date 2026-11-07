package ua.vtc.rozklad.core.substitution.internal.preference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.substitution.PreferenceKind;
import ua.vtc.rozklad.core.substitution.internal.support.CurrentTeacher;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;

@WebMvcTest(MyPreferenceController.class)
@Import(WebSliceTestConfiguration.class)
class MyPreferenceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PreferenceService preferences;

    @MockitoBean
    private CurrentTeacher currentTeacher;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void teacherReplacesTheGrid() throws Exception {
        Teacher krentsin = new Teacher("Кренцін", "Кренцін М.Д.", "Кренцін");
        given(currentTeacher.require(any())).willReturn(krentsin);
        List<PreferenceDto> grid = List.of(new PreferenceDto(1, 1, PreferenceKind.UNWANTED, "Дорога з села"));
        given(preferences.replace(eq(krentsin.getId()), eq(null), eq(grid))).willReturn(grid);

        mockMvc.perform(put("/api/me/preferences").with(TestJwts.teacher()).contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"weekday\":1,\"pair\":1,\"kind\":\"UNWANTED\",\"note\":\"Дорога з села\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("UNWANTED"));
    }

    @Test
    void saturdayIsNotAStudyDay() throws Exception {
        mockMvc.perform(put("/api/me/preferences").with(TestJwts.teacher()).contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"weekday\":6,\"pair\":1,\"kind\":\"UNAVAILABLE\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Некоректні дані"));
        verifyNoInteractions(preferences);
    }

    @Test
    void accountWithoutATeacherIsForbidden() throws Exception {
        given(currentTeacher.require(any())).willThrow(new AccessDeniedException("Обліковий запис не пов'язаний"));

        mockMvc.perform(get("/api/me/preferences").with(TestJwts.admin())).andExpect(status().isForbidden());
    }
}
