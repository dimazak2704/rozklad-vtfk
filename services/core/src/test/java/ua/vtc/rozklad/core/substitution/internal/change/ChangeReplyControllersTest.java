package ua.vtc.rozklad.core.substitution.internal.change;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.contracts.ConfirmationDecision;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.security.CurrentUser;
import ua.vtc.rozklad.core.substitution.internal.support.CurrentTeacher;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;

/** Teacher answers from the cabinet and from the bot: roles, ownership (403) and «not pending» (409). */
@WebMvcTest({MyChangeController.class, InternalChangeController.class})
@Import(WebSliceTestConfiguration.class)
class ChangeReplyControllersTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChangeQueries queries;

    @MockitoBean
    private ChangeReplies replies;

    @MockitoBean
    private CurrentTeacher currentTeacher;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void teacherConfirmsWithoutABody() throws Exception {
        given(replies.replyAsUser(eq(ChangeDtos.ID), any(CurrentUser.class), eq(ConfirmationDecision.CONFIRM), eq(null)))
                .willReturn(ChangeDtos.sample(ChangeStatus.CONFIRMED));

        mockMvc.perform(post("/api/me/changes/{id}/confirm", ChangeDtos.ID).with(TestJwts.teacher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void teacherDeclinesWithAComment() throws Exception {
        given(replies.replyAsUser(any(), any(), any(), any())).willReturn(ChangeDtos.sample(ChangeStatus.DECLINED));

        mockMvc.perform(post("/api/me/changes/{id}/decline", ChangeDtos.ID).with(TestJwts.teacher())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Маю пару в 2ОД\"}"))
                .andExpect(status().isOk());
        verify(replies).replyAsUser(eq(ChangeDtos.ID), any(CurrentUser.class), eq(ConfirmationDecision.DECLINE),
                eq("Маю пару в 2ОД"));
    }

    @Test
    void anotherTeachersChangeIsForbidden() throws Exception {
        given(replies.replyAsUser(any(), any(), any(), any()))
                .willThrow(new AccessDeniedException("Заміна призначена іншому викладачу"));

        mockMvc.perform(post("/api/me/changes/{id}/confirm", ChangeDtos.ID).with(TestJwts.teacher()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Доступ заборонено"));
    }

    @Test
    void answeringAChangeThatNoLongerWaitsIsAConflict() throws Exception {
        given(replies.replyAsUser(any(), any(), any(), any()))
                .willThrow(new ConflictException("Заміна вже не очікує відповіді (статус «Скасовано»)."));

        mockMvc.perform(post("/api/me/changes/{id}/confirm", ChangeDtos.ID).with(TestJwts.teacher()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Заміна вже не очікує відповіді (статус «Скасовано»)."));
    }

    @Test
    void tooLongCommentIsRejected() throws Exception {
        mockMvc.perform(post("/api/me/changes/{id}/decline", ChangeDtos.ID).with(TestJwts.teacher())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"" + "я".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("comment"));
        verifyNoInteractions(replies);
    }

    @Test
    void myChangesAreTheChangesOfMyTeacher() throws Exception {
        Teacher krentsin = new Teacher("Кренцін", "Кренцін М.Д.", "Кренцін");
        given(currentTeacher.require(any())).willReturn(krentsin);
        given(queries.assignedTo(krentsin.getId(), ChangeStatus.PENDING_CONFIRMATION))
                .willReturn(List.of(ChangeDtos.sample(ChangeStatus.PENDING_CONFIRMATION)));

        mockMvc.perform(get("/api/me/changes").param("status", "PENDING_CONFIRMATION").with(TestJwts.teacher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ChangeDtos.ID.toString()));
    }

    @Test
    void serviceAccountCannotUseTheCabinet() throws Exception {
        mockMvc.perform(post("/api/me/changes/{id}/confirm", ChangeDtos.ID).with(TestJwts.service()))
                .andExpect(status().isForbidden());
    }

    @Test
    void botRelaysTheAnswerOfTheTeacher() throws Exception {
        UUID teacherId = UUID.randomUUID();
        given(replies.replyFromBot(ChangeDtos.ID, teacherId, ConfirmationDecision.CONFIRM, null))
                .willReturn(ChangeDtos.sample(ChangeStatus.CONFIRMED));

        mockMvc.perform(post("/api/internal/changes/{id}/reply", ChangeDtos.ID).with(TestJwts.service())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teacherId\":\"%s\",\"decision\":\"CONFIRM\"}".formatted(teacherId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void botMustSayTheDecision() throws Exception {
        mockMvc.perform(post("/api/internal/changes/{id}/reply", ChangeDtos.ID).with(TestJwts.service())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teacherId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("decision"));
    }

    @Test
    void botAnswerForAnotherTeacherIsForbidden() throws Exception {
        given(replies.replyFromBot(any(), any(), any(), any()))
                .willThrow(new AccessDeniedException("Заміна призначена іншому викладачу"));

        mockMvc.perform(post("/api/internal/changes/{id}/reply", ChangeDtos.ID).with(TestJwts.service())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teacherId\":\"%s\",\"decision\":\"DECLINE\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyServicesUseTheInternalApi() throws Exception {
        mockMvc.perform(post("/api/internal/changes/{id}/reply", ChangeDtos.ID).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teacherId\":\"%s\",\"decision\":\"CONFIRM\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(replies);
    }
}
