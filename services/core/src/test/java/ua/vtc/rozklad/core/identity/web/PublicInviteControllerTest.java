package ua.vtc.rozklad.core.identity.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.vtc.rozklad.core.identity.InviteStatus;
import ua.vtc.rozklad.core.identity.internal.IdentityProblems;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.AcceptedInviteDto;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.InviteInfoDto;
import ua.vtc.rozklad.core.identity.internal.InviteService;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;

/** Teacher registration endpoints: 201, 409 username taken, 410 gone, 503, Ukrainian validation. */
@WebMvcTest(PublicInviteController.class)
@Import(WebSliceTestConfiguration.class)
class PublicInviteControllerTest {

    private static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde";
    private static final String BODY = "{\"username\":\"krentsin\",\"password\":\"secret-password\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private InviteService invites;

    @Test
    void inviteInfoIsPublic() throws Exception {
        given(invites.inspect(TOKEN)).willReturn(new InviteInfoDto(true, InviteStatus.ACTIVE, "Кренцін Микола Дмитрович",
                Instant.parse("2026-11-14T10:00:00Z")));

        mockMvc.perform(get("/api/public/invites/{token}", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.teacherName").value("Кренцін Микола Дмитрович"))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-14T10:00:00Z"));
    }

    @Test
    void acceptIs201WithUsername() throws Exception {
        given(invites.accept(TOKEN, "krentsin", "secret-password")).willReturn(new AcceptedInviteDto("krentsin"));

        mockMvc.perform(post("/api/public/invites/{token}/accept", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("krentsin"));
    }

    @Test
    void expiredInviteIs410() throws Exception {
        given(invites.accept(TOKEN, "krentsin", "secret-password")).willThrow(IdentityProblems.inviteGone(InviteStatus.EXPIRED));

        mockMvc.perform(post("/api/public/invites/{token}/accept", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:invite-gone"))
                .andExpect(jsonPath("$.title").value("Запрошення недійсне"))
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.detail").value("Термін дії запрошення минув. Попросіть адміністратора надіслати нове."));
    }

    @Test
    void takenUsernameIs409() throws Exception {
        given(invites.accept(TOKEN, "krentsin", "secret-password"))
                .willThrow(new ConflictException("Ім'я користувача «krentsin» уже зайняте. Виберіть інше."));

        mockMvc.perform(post("/api/public/invites/{token}/accept", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ім'я користувача «krentsin» уже зайняте. Виберіть інше."));
    }

    @Test
    void keycloakDownIs503() throws Exception {
        given(invites.accept(TOKEN, "krentsin", "secret-password"))
                .willThrow(IdentityProblems.identityUnavailable(new IllegalStateException("down")));

        mockMvc.perform(post("/api/public/invites/{token}/accept", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Сервіс входу недоступний"));
    }

    @Test
    void weakCredentialsAre400InUkrainian() throws Exception {
        mockMvc.perform(post("/api/public/invites/{token}/accept", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ко\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.errors[0].message").value("Пароль має містити від 8 до 128 символів"))
                .andExpect(jsonPath("$.errors[1].field").value("username"));
    }
}
