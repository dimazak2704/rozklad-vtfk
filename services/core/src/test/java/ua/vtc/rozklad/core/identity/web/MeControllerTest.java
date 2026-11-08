package ua.vtc.rozklad.core.identity.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.identity.CurrentUserService;
import ua.vtc.rozklad.core.identity.UserProfile;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;

/** {@code GET /api/me}: teachers and admins only. */
@WebMvcTest(MeController.class)
@Import(WebSliceTestConfiguration.class)
class MeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CurrentUserService currentUser;

    @Test
    void teacherSeesTheLinkedTeacher() throws Exception {
        UUID teacherId = UUID.randomUUID();
        given(currentUser.profile()).willReturn(new UserProfile(TestJwts.KRENTSIN_ID, "krentsin", List.of("TEACHER"),
                new TeacherRef(teacherId, "Кренцін М.Д.", "Кренцін")));

        mockMvc.perform(get("/api/me").with(TestJwts.teacher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(TestJwts.KRENTSIN_ID.toString()))
                .andExpect(jsonPath("$.roles[0]").value("TEACHER"))
                .andExpect(jsonPath("$.teacher.id").value(teacherId.toString()))
                .andExpect(jsonPath("$.teacher.shortName").value("Кренцін М.Д."));
    }

    @Test
    void adminWithoutTeacherGetsNull() throws Exception {
        given(currentUser.profile()).willReturn(new UserProfile(TestJwts.ADMIN_ID, "admin", List.of("ADMIN"), null));

        mockMvc.perform(get("/api/me").with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teacher").doesNotExist());
    }

    @Test
    void anonymousAndServiceAccountsAreRejected() throws Exception {
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/me").with(TestJwts.service())).andExpect(status().isForbidden());
    }
}
