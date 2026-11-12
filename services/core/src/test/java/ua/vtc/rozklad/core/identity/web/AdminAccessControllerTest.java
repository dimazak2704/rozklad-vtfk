package ua.vtc.rozklad.core.identity.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import ua.vtc.rozklad.core.identity.internal.AccessDtos.AccessDto;
import ua.vtc.rozklad.core.identity.internal.AccessDtos.AccessStatus;
import ua.vtc.rozklad.core.identity.internal.AccessDtos.GoogleDto;
import ua.vtc.rozklad.core.identity.internal.AccessDtos.TeacherDto;
import ua.vtc.rozklad.core.identity.internal.AccessService;
import ua.vtc.rozklad.core.identity.internal.IdentityProblems;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.PasswordLinkDto;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;

/** {@code /api/admin/access}: ADMIN only, validation, the 409 codes and the one-time password link. */
@WebMvcTest(AdminAccessController.class)
@Import(WebSliceTestConfiguration.class)
class AdminAccessControllerTest {

    private static final UUID ACCOUNT = UUID.fromString("0b0e5c1e-0000-4000-8000-000000000042");
    private static final AccessDto GRANTED = new AccessDto(ACCOUNT, null, "krentsin@vtc.vn.ua",
            "Кренцін Микола Дмитрович", List.of("TEACHER"), AccessStatus.ACTIVE, false, new GoogleDto(false), false,
            null, new TeacherDto(UUID.fromString("01a1b8ff-0000-7000-8000-000000000001"), "Кренцін М.Д.",
            "Кренцін Микола Дмитрович"));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private AccessService access;

    @Test
    void adminListsAccessWithFilters() throws Exception {
        given(access.list("кре", "TEACHER", AccessStatus.ACTIVE)).willReturn(List.of(GRANTED));

        mockMvc.perform(get("/api/admin/access").param("q", "кре").param("role", "TEACHER").param("status", "ACTIVE")
                        .with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("krentsin@vtc.vn.ua"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].google.linked").value(false))
                .andExpect(jsonPath("$[0].teacher.shortName").value("Кренцін М.Д."));
    }

    @Test
    void grantIs201WithLocation() throws Exception {
        given(access.grant(any(), eq("admin"))).willReturn(GRANTED);

        mockMvc.perform(post("/api/admin/access").with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"krentsin@vtc.vn.ua","roles":["TEACHER"],
                                 "teacherId":"01a1b8ff-0000-7000-8000-000000000001"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith(ACCOUNT.toString())))
                .andExpect(jsonPath("$.id").value(ACCOUNT.toString()));
    }

    @Test
    void grantValidatesTheAddressAndRoles() throws Exception {
        mockMvc.perform(post("/api/admin/access").with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-address\",\"roles\":[\"ROOT\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists());
        mockMvc.perform(post("/api/admin/access").with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@vtc.vn.ua\",\"roles\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("roles"));
        verifyNoInteractions(access);
    }

    @Test
    void conflictsCarryTheirCode() throws Exception {
        given(access.grant(any(), any())).willThrow(IdentityProblems.accessConflict("EMAIL_TAKEN",
                "Ця адреса вже має доступ."));
        given(access.revoke(eq(ACCOUNT), any())).willThrow(IdentityProblems.accessConflict("LAST_ADMIN",
                "Має залишитися хоча б один адміністратор, який може увійти."));

        mockMvc.perform(post("/api/admin/access").with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@vtc.vn.ua\",\"displayName\":\"А\",\"roles\":[\"ADMIN\"]}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"))
                .andExpect(jsonPath("$.detail").value("Ця адреса вже має доступ."));
        mockMvc.perform(post("/api/admin/access/{id}/revoke", ACCOUNT).with(TestJwts.admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN"));
    }

    @Test
    void selfRevokeIs422() throws Exception {
        given(access.revoke(eq(ACCOUNT), any())).willThrow(new BusinessRuleException("SELF_REVOKE",
                "Не можна відкликати власний доступ."));

        mockMvc.perform(post("/api/admin/access/{id}/revoke", ACCOUNT).with(TestJwts.admin()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SELF_REVOKE"));
    }

    @Test
    void updateRestoreUnbindAndPasswordLink() throws Exception {
        given(access.update(eq(ACCOUNT), any(), eq("admin"))).willReturn(GRANTED);
        given(access.restore(ACCOUNT, "admin")).willReturn(GRANTED);
        given(access.passwordLink(ACCOUNT, "admin")).willReturn(new PasswordLinkDto(
                "http://localhost:5180/password/abc", Instant.parse("2026-11-19T10:00:00Z")));

        mockMvc.perform(patch("/api/admin/access/{id}", ACCOUNT).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\":[\"TEACHER\",\"ADMIN\"]}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/access/{id}/restore", ACCOUNT).with(TestJwts.admin()))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/admin/access/{id}/google", ACCOUNT).with(TestJwts.admin()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/admin/access/{id}/password-link", ACCOUNT).with(TestJwts.admin()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value("http://localhost:5180/password/abc"))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-19T10:00:00Z"));
        verify(access).unbindGoogle(ACCOUNT, "admin");
    }

    @Test
    void onlyAdminsManageAccess() throws Exception {
        mockMvc.perform(get("/api/admin/access").with(TestJwts.teacher())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/access/{id}/revoke", ACCOUNT).with(TestJwts.teacher()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/access")).andExpect(status().isUnauthorized());
        verifyNoInteractions(access);
    }
}
