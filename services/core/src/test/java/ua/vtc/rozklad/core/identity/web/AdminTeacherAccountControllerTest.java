package ua.vtc.rozklad.core.identity.web;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.vtc.rozklad.core.identity.internal.TeacherAccountService;
import ua.vtc.rozklad.core.identity.internal.TeacherAccountService.TeacherAccountDto;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;

/** {@code /api/admin/teachers/{id}/account}: ADMIN only, validation and ProblemDetail errors. */
@WebMvcTest(AdminTeacherAccountController.class)
@Import(WebSliceTestConfiguration.class)
class AdminTeacherAccountControllerTest {

    private static final UUID TEACHER = UUID.fromString("01a1b8ff-0000-7000-8000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private TeacherAccountService accounts;

    @Test
    void adminLinksAnAccount() throws Exception {
        given(accounts.link(TEACHER, "krentsin", "admin")).willReturn(new TeacherAccountDto(TEACHER, "Кренцін М.Д.",
                true, TestJwts.KRENTSIN_ID, "krentsin", true));

        mockMvc.perform(put("/api/admin/teachers/{id}/account", TEACHER).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"krentsin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(true))
                .andExpect(jsonPath("$.username").value("krentsin"))
                .andExpect(jsonPath("$.userId").value(TestJwts.KRENTSIN_ID.toString()));
    }

    @Test
    void teacherCannotLinkAccounts() throws Exception {
        mockMvc.perform(put("/api/admin/teachers/{id}/account", TEACHER).with(TestJwts.teacher())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"krentsin\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void blankUsernameIs400WithFieldError() throws Exception {
        mockMvc.perform(put("/api/admin/teachers/{id}/account", TEACHER).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("username"))
                .andExpect(jsonPath("$.errors[0].message").value("Вкажіть ім'я користувача"));
    }

    @Test
    void unknownAccountIs422() throws Exception {
        given(accounts.link(TEACHER, "nobody", "admin")).willThrow(new BusinessRuleException("ACCOUNT_NOT_FOUND",
                "Обліковий запис «nobody» не знайдено на сервері входу."));

        mockMvc.perform(put("/api/admin/teachers/{id}/account", TEACHER).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"nobody\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void unlinkIs204() throws Exception {
        mockMvc.perform(delete("/api/admin/teachers/{id}/account", TEACHER).with(TestJwts.admin()))
                .andExpect(status().isNoContent());

        verify(accounts).unlink(TEACHER, "admin");
    }
}
