package ua.vtc.rozklad.core.timetable.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.catalog.DisciplineRef;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.RoomRef;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;
import ua.vtc.rozklad.core.timetable.internal.version.MoveCommand;
import ua.vtc.rozklad.core.timetable.internal.version.TemplateLessonCommand;
import ua.vtc.rozklad.core.timetable.internal.version.TemplateLessonDto;
import ua.vtc.rozklad.core.timetable.internal.version.TemplateLessonMutationResponse;
import ua.vtc.rozklad.core.timetable.internal.version.TemplateLessonService;

@WebMvcTest(TemplateLessonController.class)
@Import(WebSliceTestConfiguration.class)
class TemplateLessonControllerTest {

    private static final UUID VERSION = UUID.fromString("0190f0a0-0000-7000-8000-000000000100");
    private static final UUID LESSON = UUID.fromString("0190f0a0-0000-7000-8000-000000000101");
    private static final UUID OTHER_LESSON = UUID.fromString("0190f0a0-0000-7000-8000-000000000102");
    private static final UUID GROUP = UUID.fromString("0190f0a0-0000-7000-8000-000000000103");
    private static final UUID DISCIPLINE = UUID.fromString("0190f0a0-0000-7000-8000-000000000104");
    private static final TemplateLessonDto DTO = new TemplateLessonDto(LESSON, 1, 2, Parity.BOTH,
            new GroupRef(GROUP, "4КІ1", 4), 0, new DisciplineRef(DISCIPLINE, "СТП", "Сучасні технології проєктування",
            null), new TeacherRef(UUID.randomUUID(), "Кренцін М.Д.", "Кренцін"),
            new RoomRef(UUID.randomUUID(), "6", RoomType.COMPUTER_LAB), LessonKind.LAB, null, false, false, null, 3);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private TemplateLessonService lessons;

    @Test
    void createdLessonComesWithLocationAndConflictsOfTheSlot() throws Exception {
        ConflictDto window = new ConflictDto(ConflictKind.WINDOW, ConflictDto.Severity.SOFT,
                "Вікно у групи 4КІ1 між 1 і 3 парою (понеділок)", List.of(LESSON), null, 1, 2);
        given(lessons.create(eq(VERSION), any(TemplateLessonCommand.class)))
                .willReturn(new TemplateLessonMutationResponse(DTO, List.of(window)));

        mockMvc.perform(post("/api/admin/timetable/versions/{v}/lessons", VERSION).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"weekday":1,"pair":2,"parity":"BOTH","groupId":"%s","disciplineId":"%s",
                                 "lessonKind":"LAB"}
                                """.formatted(GROUP, DISCIPLINE)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/admin/timetable/versions/%s/lessons/%s"
                        .formatted(VERSION, LESSON)))
                .andExpect(jsonPath("$.lesson.group.code").value("4КІ1"))
                .andExpect(jsonPath("$.lesson.discipline.shortName").value("СТП"))
                .andExpect(jsonPath("$.conflicts[0].kind").value("WINDOW"))
                .andExpect(jsonPath("$.conflicts[0].severity").value("SOFT"))
                .andExpect(jsonPath("$.conflicts[0].weekday").value(1))
                .andExpect(jsonPath("$.conflicts[0].date").doesNotExist());
        verify(lessons).create(VERSION, new TemplateLessonCommand(1, 2, Parity.BOTH, GROUP, 0, DISCIPLINE, null, null,
                LessonKind.LAB, null, false, false, null));
    }

    @Test
    void invalidLessonIsA400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/admin/timetable/versions/{v}/lessons", VERSION).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"weekday":6,"pair":2,"groupId":"%s","disciplineId":"%s"}
                                """.formatted(GROUP, DISCIPLINE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("parity"))
                .andExpect(jsonPath("$.errors[1].field").value("weekday"));
    }

    @Test
    void moveIntoAHardConflictIsA409WithTheConflicts() throws Exception {
        ConflictDto clash = ConflictDto.hardInTemplate(ConflictKind.TEACHER,
                "Викладач Кренцін М.Д. одночасно має кілька занять: 4КІ1 СТП, 4КІ2 НДЕКСМ (вівторок, 3 пара)",
                List.of(LESSON, OTHER_LESSON), 2, 3);
        given(lessons.move(eq(VERSION), eq(LESSON), any(MoveCommand.class), any()))
                .willThrow(new ConflictException("Перенесення створює конфлікти в розкладі", List.of(clash)));

        mockMvc.perform(post("/api/admin/timetable/versions/{v}/lessons/{l}/move", VERSION, LESSON)
                        .with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekday\":2,\"pair\":3,\"parity\":\"BOTH\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:schedule-conflict"))
                .andExpect(jsonPath("$.detail").value("Перенесення створює конфлікти в розкладі"))
                .andExpect(jsonPath("$.conflicts[0].kind").value("TEACHER"))
                .andExpect(jsonPath("$.conflicts[0].severity").value("HARD"))
                .andExpect(jsonPath("$.conflicts[0].weekday").value(2))
                .andExpect(jsonPath("$.conflicts[0].pair").value(3))
                .andExpect(jsonPath("$.conflicts[0].lessonIds.length()").value(2));
    }

    @Test
    void dryRunOnlyPreviewsTheMove() throws Exception {
        given(lessons.previewMove(VERSION, LESSON, new MoveCommand(3, 1, Parity.NUMERATOR, null)))
                .willReturn(new TemplateLessonMutationResponse(DTO, List.of()));

        mockMvc.perform(post("/api/admin/timetable/versions/{v}/lessons/{l}/move", VERSION, LESSON)
                        .param("dryRun", "true").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekday\":3,\"pair\":1,\"parity\":\"NUMERATOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lesson.id").value(LESSON.toString()))
                .andExpect(jsonPath("$.conflicts").isEmpty());
        verify(lessons, never()).move(any(), any(), any(), any());
    }

    @Test
    void deleteAnswersWithTheConflictsLeftInTheSlot() throws Exception {
        given(lessons.delete(VERSION, LESSON)).willReturn(new TemplateLessonMutationResponse(null, List.of()));

        mockMvc.perform(delete("/api/admin/timetable/versions/{v}/lessons/{l}", VERSION, LESSON)
                        .with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lesson").doesNotExist())
                .andExpect(jsonPath("$.conflicts").isArray());
    }

    @Test
    void onlyAdminsEditTheTimetable() throws Exception {
        mockMvc.perform(get("/api/admin/timetable/versions/{v}/lessons", VERSION))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/timetable/versions/{v}/lessons", VERSION).with(TestJwts.teacher()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/timetable/versions/{v}/lessons", VERSION).with(TestJwts.service()))
                .andExpect(status().isForbidden());
    }
}
