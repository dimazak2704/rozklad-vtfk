package ua.vtc.rozklad.core.substitution.internal.change;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.vtc.rozklad.contracts.ChangeStatus;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;

/** Security, validation and status codes of {@code /api/admin/changes}. */
@WebMvcTest(AdminChangeController.class)
@Import(WebSliceTestConfiguration.class)
class AdminChangeControllerTest {

    private static final String REPLACE_TEACHER = """
            {"kind":"REPLACE_TEACHER","date":"2026-10-19","pairFrom":2,"groupId":"%s",
             "originalLessonId":"%s","newTeacherId":"%s","reasonCode":"ABSENT"}
            """.formatted(ChangeDtos.ID, ChangeDtos.ID, ChangeDtos.ID);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChangeCommands commands;

    @MockitoBean
    private ChangeQueries queries;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private RecommendationLog recommendations;

    @BeforeEach
    void nothingWasShown() {
        given(recommendations.before(any())).willReturn(RecommendationLog.Shown.NONE);
    }

    @Test
    void anonymousCallIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/changes")).andExpect(status().isUnauthorized());
    }

    @Test
    void teacherCannotManageChanges() throws Exception {
        mockMvc.perform(post("/api/admin/changes").with(TestJwts.teacher())
                        .contentType(MediaType.APPLICATION_JSON).content(REPLACE_TEACHER))
                .andExpect(status().isForbidden());
        verifyNoInteractions(commands);
    }

    @Test
    void createdChangeIs201WithLocationAndThePendingStatus() throws Exception {
        given(commands.create(any(), eq("admin"))).willReturn(ChangeDtos.sample(ChangeStatus.PENDING_CONFIRMATION));

        mockMvc.perform(post("/api/admin/changes").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(REPLACE_TEACHER))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/admin/changes/" + ChangeDtos.ID)))
                .andExpect(jsonPath("$.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.new.teacher.shortName").value("Непийвода М.В."))
                .andExpect(jsonPath("$.original.teacher.displayName").value("Кренцін"))
                .andExpect(jsonPath("$.history[0].actor").value("admin"))
                .andExpect(jsonPath("$.printedReason").value("Відп."));
    }

    @Test
    void kindSpecificFieldsAreValidatedPerField() throws Exception {
        String withoutTeacher = """
                {"kind":"REPLACE_TEACHER","date":"2026-10-19","pairFrom":2,"groupId":"%s",
                 "originalLessonId":"%s","reasonCode":"ABSENT"}
                """.formatted(ChangeDtos.ID, ChangeDtos.ID);

        mockMvc.perform(post("/api/admin/changes").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(withoutTeacher))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("newTeacherId"))
                .andExpect(jsonPath("$.errors[0].message").value("Вкажіть викладача, який проведе пару"));
        verifyNoInteractions(commands);
    }

    @Test
    void aBatchIs201WithEveryChangeAndLogsEachDecision() throws Exception {
        given(commands.createAll(any(), eq("admin"))).willReturn(List.of(ChangeDtos.sample(ChangeStatus.CONFIRMED),
                ChangeDtos.sample(ChangeStatus.PENDING_CONFIRMATION)));

        mockMvc.perform(post("/api/admin/changes/batch").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changes\":[" + REPLACE_TEACHER + "," + REPLACE_TEACHER + "]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].status").value("PENDING_CONFIRMATION"));
        verify(recommendations, org.mockito.Mockito.times(2)).after(any(), any(), any());
    }

    @Test
    void aBatchValidatesEveryItemAndIsNeverEmpty() throws Exception {
        mockMvc.perform(post("/api/admin/changes/batch").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"changes\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("changes"));
        mockMvc.perform(post("/api/admin/changes/batch").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changes\":[" + REPLACE_TEACHER.replace("\"newTeacherId\"", "\"x\"") + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("changes[0].newTeacherId"));
        verifyNoInteractions(commands);
    }

    @Test
    void pairOutsideTheDayIsRejected() throws Exception {
        String ninthPair = REPLACE_TEACHER.replace("\"pairFrom\":2", "\"pairFrom\":9");

        mockMvc.perform(post("/api/admin/changes").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(ninthPair))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("pairFrom"));
    }

    @Test
    void busySlotIs409WithConflicts() throws Exception {
        given(commands.create(any(), any())).willThrow(ConflictException.schedule(List.of(ConflictDto.hard(
                ConflictKind.TEACHER, "Непийвода М.В. уже має 2 пару: група 4КІ2 (НДКС)", List.of(ChangeDtos.ID),
                LocalDate.of(2026, 10, 19), 2))));

        mockMvc.perform(post("/api/admin/changes").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(REPLACE_TEACHER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflicts[0].kind").value("TEACHER"))
                .andExpect(jsonPath("$.conflicts[0].message").value("Непийвода М.В. уже має 2 пару: група 4КІ2 (НДКС)"));
    }

    @Test
    void illegalTransitionIs422WithCode() throws Exception {
        given(commands.confirm(ChangeDtos.ID, "admin")).willThrow(new BusinessRuleException(
                ChangeStatusMachine.ILLEGAL_TRANSITION, "Зміну зі статусом «Застосовано» не можна перевести у статус «Підтверджено»."));

        mockMvc.perform(post("/api/admin/changes/{id}/confirm", ChangeDtos.ID).with(TestJwts.admin()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CHANGE_ILLEGAL_TRANSITION"));
    }

    @Test
    void cancellingIs204() throws Exception {
        mockMvc.perform(delete("/api/admin/changes/{id}", ChangeDtos.ID).with(TestJwts.admin()))
                .andExpect(status().isNoContent());
        verify(commands).cancel(ChangeDtos.ID, "admin");
    }

    @Test
    void cancellingAnAppliedChangeIs422() throws Exception {
        willThrow(new BusinessRuleException("CHANGE_ALREADY_APPLIED", "Зміна вже застосована"))
                .given(commands).cancel(ChangeDtos.ID, "admin");

        mockMvc.perform(delete("/api/admin/changes/{id}", ChangeDtos.ID).with(TestJwts.admin()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CHANGE_ALREADY_APPLIED"));
    }

    @Test
    void editNeedsAReason() throws Exception {
        mockMvc.perform(put("/api/admin/changes/{id}", ChangeDtos.ID).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newRoomId\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reasonCode"));
    }

    @Test
    void editWithoutVersionIsRejectedSoConcurrentEditsAreNotOverwritten() throws Exception {
        mockMvc.perform(put("/api/admin/changes/{id}", ChangeDtos.ID).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"ABSENT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("version"));
        verifyNoInteractions(commands);
    }

    @Test
    void listFiltersByDateAndStatus() throws Exception {
        given(queries.forDate(LocalDate.of(2026, 10, 19), ChangeStatus.CONFIRMED))
                .willReturn(List.of(ChangeDtos.sample(ChangeStatus.CONFIRMED)));

        mockMvc.perform(get("/api/admin/changes").param("date", "2026-10-19").param("status", "CONFIRMED")
                        .with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].group.code").value("4КІ1"));
    }
}
