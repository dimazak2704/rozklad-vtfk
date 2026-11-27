package ua.vtc.rozklad.core.timetable.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.timetable.internal.generation.LoadFitCheck;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;
import ua.vtc.rozklad.core.timetable.internal.generation.GenerationJobDto;
import ua.vtc.rozklad.core.timetable.internal.generation.GenerationRequestCommand;
import ua.vtc.rozklad.core.timetable.internal.generation.GenerationService;

@WebMvcTest(GenerationJobController.class)
@Import(WebSliceTestConfiguration.class)
class GenerationJobControllerTest {

    private static final UUID SEMESTER = UUID.fromString("0190f0a0-0000-7000-8000-000000000400");
    private static final UUID JOB = UUID.fromString("0190f0a0-0000-7000-8000-000000000401");
    private static final UUID GROUP = UUID.fromString("0190f0a0-0000-7000-8000-000000000402");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private GenerationService generation;

    @Test
    void startAccepts202WithJobId() throws Exception {
        given(generation.start(any(GenerationRequestCommand.class), eq("admin"))).willReturn(job(GenerationStatus.QUEUED));

        mockMvc.perform(post("/api/admin/generation/jobs").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"semesterId":"%s","scope":{"groupIds":["%s"],"courses":[4]},"timeLimitSeconds":120,
                                 "weights":{"windows":10,"brokenDays":20,"latePairs":3,"preferences":5,"homeRoom":1}}
                                """.formatted(SEMESTER, GROUP)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/admin/generation/jobs/" + JOB))
                .andExpect(jsonPath("$.jobId").value(JOB.toString()));
        verify(generation).start(new GenerationRequestCommand(SEMESTER, List.of(GROUP), List.of(4), null, 120,
                new GenerationWeights(10, 20, 3, 5, 1)), "admin");
    }

    /** Auto mode: without {@code timeLimitSeconds} the request is valid and carries no limit. */
    @Test
    void startWithoutTimeLimitIsAutoMode() throws Exception {
        given(generation.start(any(GenerationRequestCommand.class), eq("admin"))).willReturn(job(GenerationStatus.QUEUED));

        mockMvc.perform(post("/api/admin/generation/jobs").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"semesterId":"%s","scope":{"courses":[4]}}
                                """.formatted(SEMESTER)))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/admin/generation/jobs").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"semesterId":"%s","scope":{"courses":[4]},"timeLimitSeconds":null}
                                """.formatted(SEMESTER)))
                .andExpect(status().isAccepted());

        verify(generation, times(2)).start(new GenerationRequestCommand(SEMESTER, List.of(), List.of(4), null, null,
                null), "admin");
    }

    @Test
    void anOldRequestKeepsItsLimitWithinTheOldRange() throws Exception {
        mockMvc.perform(post("/api/admin/generation/jobs").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"semesterId":"%s","timeLimitSeconds":601}
                                """.formatted(SEMESTER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("timeLimitSeconds"));
        verify(generation, never()).start(any(), any());
    }

    @Test
    void timeLimitAndWeightsAreValidated() throws Exception {
        mockMvc.perform(post("/api/admin/generation/jobs").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"semesterId":"%s","timeLimitSeconds":5,
                                 "weights":{"windows":-1,"brokenDays":20,"latePairs":3,"preferences":5,"homeRoom":1}}
                                """.formatted(SEMESTER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("timeLimitSeconds"))
                .andExpect(jsonPath("$.errors[1].field").value("weights.windows"));
        verify(generation, never()).start(any(), any());
    }

    /** genfix: the new weights are optional; absent ones take defaults, teacher windows follow windows. */
    @Test
    void newWeightsAreOptionalAndValidated() throws Exception {
        given(generation.start(any(GenerationRequestCommand.class), eq("admin"))).willReturn(job(GenerationStatus.QUEUED));

        mockMvc.perform(post("/api/admin/generation/jobs").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"semesterId":"%s","timeLimitSeconds":120,
                                 "weights":{"windows":12,"brokenDays":10,"latePairs":3,"preferences":5,"homeRoom":1,
                                            "stability":0,"teacherShiftWait":2,"specialRooms":0}}
                                """.formatted(SEMESTER)))
                .andExpect(status().isAccepted());
        verify(generation).start(new GenerationRequestCommand(SEMESTER, List.of(), List.of(), null, 120,
                new GenerationWeights(12, 10, 3, 5, 1, 12, 2, 0, null, null, null, null, null, 0)), "admin");

        mockMvc.perform(post("/api/admin/generation/jobs").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"semesterId":"%s","timeLimitSeconds":120,
                                 "weights":{"windows":10,"brokenDays":10,"latePairs":3,"preferences":5,"homeRoom":1,
                                            "teacherOverload":-1}}
                                """.formatted(SEMESTER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("weights.teacherOverload"));
    }

    /** B9: a load that does not fit the grid is a 422 listing every problem, so the form can show them. */
    @Test
    void loadThatDoesNotFitIs422WithProblems() throws Exception {
        LoadFitCheck.Problem problem = new LoadFitCheck.Problem(LoadFitCheck.Kind.GROUP, GROUP.toString(), "2ТК", 20.5,
                19, "Група 2ТК: 20,5 пари на тиждень, а в її зміні 19 слотів.");
        given(generation.start(any(GenerationRequestCommand.class), eq("admin"))).willThrow(new BusinessRuleException(
                LoadFitCheck.LOAD_EXCEEDS_SLOTS, "Навантаження не вміщується в сітку занять (1). Виправте …",
                Map.of("problems", List.of(problem))));

        mockMvc.perform(post("/api/admin/generation/jobs").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"semesterId":"%s","timeLimitSeconds":120}
                                """.formatted(SEMESTER)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:business-rule"))
                .andExpect(jsonPath("$.title").value("Порушено правило"))
                .andExpect(jsonPath("$.code").value("GENERATION_LOAD_EXCEEDS_SLOTS"))
                .andExpect(jsonPath("$.problems[0].kind").value("GROUP"))
                .andExpect(jsonPath("$.problems[0].label").value("2ТК"))
                .andExpect(jsonPath("$.problems[0].requiredPairs").value(20.5))
                .andExpect(jsonPath("$.problems[0].availablePairs").value(19))
                .andExpect(jsonPath("$.problems[0].message").value(problem.message()));
    }

    @Test
    void cancelIsAcceptedAndAFinishedJobIsAConflict() throws Exception {
        given(generation.cancel(JOB)).willReturn(job(GenerationStatus.CANCELLED));
        mockMvc.perform(post("/api/admin/generation/jobs/{id}/cancel", JOB).with(TestJwts.admin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.stopRequested").value(false));

        given(generation.cancel(JOB)).willThrow(new ConflictException("Генерацію вже завершено: зупинити її не можна."));
        mockMvc.perform(post("/api/admin/generation/jobs/{id}/cancel", JOB).with(TestJwts.admin()))
                .andExpect(status().isConflict());
    }

    @Test
    void jobShowsProgressForTheLiveChart() throws Exception {
        given(generation.list(SEMESTER)).willReturn(List.of(job(GenerationStatus.RUNNING)));

        mockMvc.perform(get("/api/admin/generation/jobs").param("semesterId", SEMESTER.toString())
                        .with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("RUNNING"))
                .andExpect(jsonPath("$[0].bestScore").value("-2hard/-120soft"))
                .andExpect(jsonPath("$[0].progress[0].t").value(2.0))
                .andExpect(jsonPath("$[0].progress[0].hard").value(-2))
                .andExpect(jsonPath("$[0].hardScore").value(-2))
                .andExpect(jsonPath("$[0].scope.courses[0]").value(4))
                .andExpect(jsonPath("$[0].groups[0].code").value("4КІ1"))
                .andExpect(jsonPath("$[0].weights.teacherWindows").value(10))
                .andExpect(jsonPath("$[0].weights.stability").value(1))
                .andExpect(jsonPath("$[0].timeLimitSeconds").value(120))
                .andExpect(jsonPath("$[0].stopRequested").value(false))
                .andExpect(jsonPath("$[0].stopReason").doesNotExist())
                .andExpect(jsonPath("$[0].baseHardScore").value(-4))
                .andExpect(jsonPath("$[0].baseSoftScore").value(-300));
        mockMvc.perform(get("/api/admin/generation/jobs").with(TestJwts.teacher())).andExpect(status().isForbidden());
    }

    private static GenerationJobDto job(GenerationStatus status) {
        return new GenerationJobDto(JOB, SEMESTER, status, Instant.parse("2026-11-07T10:00:00Z"),
                Instant.parse("2026-11-07T10:00:03Z"), null, "-2hard/-120soft",
                List.of(new GenerationJobDto.ProgressPoint(2.0, -2, -120)), null, null, 120, null, null, null, null,
                "admin", new GenerationJobDto.Scope(List.of(GROUP), List.of(4)),
                List.of(new GroupRef(GROUP, "4КІ1", 4)), GenerationWeights.DEFAULT, -2L, -120L, null, false, false,
                null, -4L, -300L);
    }
}
