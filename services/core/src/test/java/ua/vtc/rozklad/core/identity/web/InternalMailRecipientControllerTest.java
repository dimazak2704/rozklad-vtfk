package ua.vtc.rozklad.core.identity.web;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
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
import ua.vtc.rozklad.core.identity.internal.IdentityProblems;
import ua.vtc.rozklad.core.identity.internal.MailRecipientService;
import ua.vtc.rozklad.core.identity.internal.MailRecipientService.MailRecipientDto;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;

/** {@code GET /api/internal/mail-recipients}: only the bot's service account, exactly one parameter. */
@WebMvcTest(InternalMailRecipientController.class)
@Import(WebSliceTestConfiguration.class)
class InternalMailRecipientControllerTest {

    private static final String PATH = "/api/internal/mail-recipients";
    private static final UUID TEACHER_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MailRecipientService recipients;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void notificationGetsTheTeachersAddress() throws Exception {
        given(recipients.forTeacher(TEACHER_ID)).willReturn(List.of(recipient("krentsin@vtc.vn.ua")));

        mockMvc.perform(get(PATH).param("teacherId", TEACHER_ID.toString()).with(TestJwts.service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$[0].teacherId").value(TEACHER_ID.toString()))
                .andExpect(jsonPath("$[0].email").value("krentsin@vtc.vn.ua"))
                .andExpect(jsonPath("$[0].googleLinked").value(true))
                .andExpect(jsonPath("$[0].roles[0]").value("TEACHER"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].revoked").value(false))
                .andExpect(jsonPath("$[0].demo").value(false));
    }

    @Test
    void byAccountAndAdmins() throws Exception {
        given(recipients.forAccount(ACCOUNT_ID)).willReturn(List.of());
        given(recipients.admins()).willReturn(List.of(recipient("dispatcher@vtc.vn.ua")));

        mockMvc.perform(get(PATH).param("accountId", ACCOUNT_ID.toString()).with(TestJwts.service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get(PATH).param("role", "ADMIN").with(TestJwts.service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("dispatcher@vtc.vn.ua"));
    }

    @Test
    void exactlyOneParameter() throws Exception {
        mockMvc.perform(get(PATH).with(TestJwts.service()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("teacherId"));
        mockMvc.perform(get(PATH).param("teacherId", TEACHER_ID.toString()).param("role", "ADMIN")
                        .with(TestJwts.service()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(recipients);
    }

    @Test
    void onlyAdminsCanBeListed() throws Exception {
        mockMvc.perform(get(PATH).param("role", "TEACHER").with(TestJwts.service()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("role"));
        mockMvc.perform(get(PATH).param("teacherId", "not-a-uuid").with(TestJwts.service()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(recipients);
    }

    @Test
    void authDownIs503() throws Exception {
        given(recipients.admins()).willThrow(IdentityProblems.identityLookupUnavailable(null));

        mockMvc.perform(get(PATH).param("role", "ADMIN").with(TestJwts.service()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:identity-unavailable"));
    }

    @Test
    void otherServicesAndPeopleAreRefused() throws Exception {
        mockMvc.perform(get(PATH).param("role", "ADMIN").with(TestJwts.importerService()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(PATH).param("role", "ADMIN")
                        .with(TestJwts.user(UUID.randomUUID(), "service-account-x", "SERVICE")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(PATH).param("role", "ADMIN").with(TestJwts.admin()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(PATH).param("role", "ADMIN").with(TestJwts.teacher()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(PATH).param("role", "ADMIN")).andExpect(status().isUnauthorized());
        verifyNoInteractions(recipients);
    }

    private static MailRecipientDto recipient(String email) {
        return new MailRecipientDto(ACCOUNT_ID, TEACHER_ID, "Кренцін М.Д.", null, email, true, List.of("TEACHER"),
                true, false, false);
    }
}
