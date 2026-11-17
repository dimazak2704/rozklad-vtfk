package ua.vtc.rozklad.auth.selfservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.vtc.rozklad.auth.config.AuthServerProperties;
import ua.vtc.rozklad.auth.login.ClientAddress;
import ua.vtc.rozklad.auth.selfservice.OwnPasswordRefusal.Problem;
import ua.vtc.rozklad.auth.support.PropertiesFixture;

/** The own-account API over HTTP: who may call it, the request it reads and the problems it answers. */
@WebMvcTest(OwnAccountController.class)
@Import({OwnAccountSecurityConfig.class, OwnAccountControllerTest.Properties.class})
class OwnAccountControllerTest {

    private static final UUID ID = UUID.fromString("a0000000-0000-4000-8000-000000000002");
    private static final String BODY = """
            {"currentPassword":"Current-Password-2026","newPassword":"Brand-New-Password-7"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OwnPasswordService passwords;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @TestConfiguration
    static class Properties {

        @Bean
        AuthServerProperties authServerProperties() {
            return PropertiesFixture.dev();
        }
    }

    @Test
    void aTeacherChangesTheirPasswordAndKeepsTheSessionOfThisBrowser() throws Exception {
        MockHttpSession session = new MockHttpSession(null, "this-browser");

        mockMvc.perform(post("/api/account/password").with(person("TEACHER")).session(session)
                        .header("X-Forwarded-For", "198.51.100.7")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent());

        var address = ArgumentCaptor.forClass(ClientAddress.class);
        verify(passwords).change(eq(ID), eq("Current-Password-2026"), eq("Brand-New-Password-7"), address.capture(),
                eq("this-browser"));
        assertThat(address.getValue().ip()).isNotNull();
    }

    @Test
    void anAdminMayChangeTheirsToo() throws Exception {
        mockMvc.perform(post("/api/account/password").with(person("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent());
        verify(passwords).change(eq(ID), any(), any(), any(), isNull());
    }

    @Test
    void theStatusTellsWhetherThereIsAPasswordToChange() throws Exception {
        given(passwords.info(ID)).willReturn(new OwnPasswordService.Info(OwnPasswordService.Status.NO_PASSWORD, 12,
                64));

        mockMvc.perform(get("/api/account/password").with(person("TEACHER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_PASSWORD"))
                .andExpect(jsonPath("$.minLength").value(12))
                .andExpect(jsonPath("$.maxLength").value(64));
    }

    @Test
    void aWrongCurrentPasswordIsA422ProblemWithAStableCode() throws Exception {
        willThrow(new OwnPasswordRefusal(Problem.CURRENT_PASSWORD_WRONG)).given(passwords)
                .change(any(), any(), any(), any(), any());

        mockMvc.perform(post("/api/account/password").with(person("TEACHER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:rozklad:auth:current-password-wrong"))
                .andExpect(jsonPath("$.code").value("current-password-wrong"))
                .andExpect(jsonPath("$.detail").value("Поточний пароль неправильний."))
                .andExpect(jsonPath("$.reason").doesNotExist());
    }

    @Test
    void aPolicyViolationNamesTheReason() throws Exception {
        willThrow(new OwnPasswordRefusal(Problem.PASSWORD_REJECTED, "Цей пароль надто поширений. Придумайте інший.",
                "too-common")).given(passwords).change(any(), any(), any(), any(), any());

        mockMvc.perform(post("/api/account/password").with(person("TEACHER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("password-rejected"))
                .andExpect(jsonPath("$.reason").value("too-common"))
                .andExpect(jsonPath("$.detail").value("Цей пароль надто поширений. Придумайте інший."));
    }

    @Test
    void tooManyAttemptsIs429() throws Exception {
        willThrow(new OwnPasswordRefusal(Problem.TOO_MANY_ATTEMPTS)).given(passwords)
                .change(any(), any(), any(), any(), any());

        mockMvc.perform(post("/api/account/password").with(person("TEACHER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("too-many-attempts"));
    }

    @Test
    void anEmptyBodyReachesTheServiceAsMissingFields() throws Exception {
        willThrow(new OwnPasswordRefusal(Problem.VALIDATION)).given(passwords)
                .change(any(), isNull(), isNull(), any(), any());

        mockMvc.perform(post("/api/account/password").with(person("TEACHER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation"));
    }

    @Test
    void withoutATokenTheAnswerIs401() throws Exception {
        mockMvc.perform(post("/api/account/password").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/account/password")).andExpect(status().isUnauthorized());
        verifyNoInteractions(passwords);
    }

    @Test
    void serviceTokensAndTokensWithoutAStaffRoleAre403() throws Exception {
        mockMvc.perform(post("/api/account/password")
                        .with(jwt().jwt(token -> token.subject("rozklad-notification")
                                .claim("roles", List.of("SERVICE", "TEACHER"))))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/account/password")
                        .with(jwt().jwt(token -> token.subject(ID.toString()).claim("roles", List.of("SERVICE"))))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/account/password").with(jwt().jwt(token -> token.subject(ID.toString()))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(passwords);
    }

    @Test
    void theViteOriginOfDevelopmentMayCallWithTheSessionCookie() throws Exception {
        mockMvc.perform(options("/api/account/password")
                        .header("Origin", "http://localhost:5180")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5180"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        mockMvc.perform(options("/api/account/password")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theSecurityRulesInIsolation() {
        assertThat(OwnAccountSecurityConfig.isAccountId(ID.toString())).isTrue();
        assertThat(OwnAccountSecurityConfig.isAccountId("rozklad-core")).isFalse();
        assertThat(OwnAccountSecurityConfig.isAccountId(null)).isFalse();
        assertThat(OwnAccountSecurityConfig.hasStaffRole(List.of("TEACHER"))).isTrue();
        assertThat(OwnAccountSecurityConfig.hasStaffRole(List.of("SERVICE"))).isFalse();
        assertThat(OwnAccountSecurityConfig.hasStaffRole("ADMIN")).isFalse();
        assertThat(OwnAccountSecurityConfig.hasStaffRole(null)).isFalse();
        assertThat(new OwnAccountController.ChangeRequest("secret-1", "secret-2").toString())
                .doesNotContain("secret");
    }

    private static JwtRequestPostProcessor person(String role) {
        return jwt().jwt(token -> token.subject(ID.toString()).claim("roles", List.of(role)));
    }
}
