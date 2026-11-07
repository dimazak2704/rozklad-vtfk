package ua.vtc.rozklad.core.timetable.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import ua.vtc.rozklad.contracts.GenerationStatus;
import ua.vtc.rozklad.contracts.events.GenerationWeights;
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

    @Test
    void cancelIsAcceptedAndAFinishedJobIsAConflict() throws Exception {
        given(generation.cancel(JOB)).willReturn(job(GenerationStatus.CANCELLED));
        mockMvc.perform(post("/api/admin/generation/jobs/{id}/cancel", JOB).with(TestJwts.admin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        given(generation.cancel(JOB)).willThrow(new ConflictException("Генерацію вже завершено: скасувати її не можна."));
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
                .andExpect(jsonPath("$[0].progress[0].hard").value(-2));
        mockMvc.perform(get("/api/admin/generation/jobs").with(TestJwts.teacher())).andExpect(status().isForbidden());
    }

    private static GenerationJobDto job(GenerationStatus status) {
        return new GenerationJobDto(JOB, SEMESTER, status, Instant.parse("2026-11-07T10:00:00Z"),
                Instant.parse("2026-11-07T10:00:03Z"), null, "-2hard/-120soft",
                List.of(new GenerationJobDto.ProgressPoint(2.0, -2, -120)), null, null, 120, null, null, null, null,
                "admin");
    }
}
