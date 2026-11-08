package ua.vtc.rozklad.core.timetable.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
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
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.ConflictDto;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.ConflictKind;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;
import ua.vtc.rozklad.core.timetable.VersionSource;
import ua.vtc.rozklad.core.timetable.VersionStatus;
import ua.vtc.rozklad.core.timetable.internal.metrics.MetricsDto;
import ua.vtc.rozklad.core.timetable.internal.publication.PublicationResult;
import ua.vtc.rozklad.core.timetable.internal.publication.PublicationService;
import ua.vtc.rozklad.core.timetable.internal.version.TimetableVersionDto;
import ua.vtc.rozklad.core.timetable.internal.version.TimetableVersionService;
import ua.vtc.rozklad.core.timetable.internal.version.VersionDiffDto;

@WebMvcTest(TimetableVersionController.class)
@Import(WebSliceTestConfiguration.class)
class TimetableVersionControllerTest {

    private static final UUID SEMESTER = UUID.fromString("0190f0a0-0000-7000-8000-000000000200");
    private static final UUID VERSION = UUID.fromString("0190f0a0-0000-7000-8000-000000000201");
    private static final UUID LESSON = UUID.fromString("0190f0a0-0000-7000-8000-000000000202");
    private static final MetricsDto METRICS = new MetricsDto(42, 12, 15, 9, 5,
            List.of(new MetricsDto.GroupMetrics(new GroupRef(UUID.randomUUID(), "4КІ1", 4), 3, 2, 1, 4)));
    private static final TimetableVersionDto DRAFT = new TimetableVersionDto(VERSION, SEMESTER, "Чернетка І семестру",
            VersionStatus.DRAFT, VersionSource.MANUAL, null, null, null, null, null,
            Instant.parse("2026-11-07T09:00:00Z"), 640, 1, 57, METRICS);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private TimetableVersionService versions;

    @MockitoBean
    private PublicationService publication;

    @Test
    void listShowsQualityOfEveryVersion() throws Exception {
        given(versions.list(SEMESTER)).willReturn(List.of(DRAFT));

        mockMvc.perform(get("/api/admin/timetable/versions").param("semesterId", SEMESTER.toString())
                        .with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("DRAFT"))
                .andExpect(jsonPath("$[0].lessonCount").value(640))
                .andExpect(jsonPath("$[0].hardConflicts").value(1))
                .andExpect(jsonPath("$[0].softWarnings").value(57))
                .andExpect(jsonPath("$[0].metrics.brokenDays").value(15))
                .andExpect(jsonPath("$[0].metrics.perGroup[0].group.code").value("4КІ1"));
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        given(versions.create(SEMESTER, "Чернетка І семестру", null)).willReturn(DRAFT);

        mockMvc.perform(post("/api/admin/timetable/versions").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"semesterId\":\"%s\",\"name\":\"Чернетка І семестру\"}".formatted(SEMESTER)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/admin/timetable/versions/" + VERSION))
                .andExpect(jsonPath("$.name").value("Чернетка І семестру"));
    }

    @Test
    void createWithoutNameIsRejected() throws Exception {
        mockMvc.perform(post("/api/admin/timetable/versions").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"semesterId\":\"%s\",\"name\":\" \"}".formatted(SEMESTER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void publishingADraftWithHardConflictsIsA409WithConflicts() throws Exception {
        ConflictDto shift = ConflictDto.hardInTemplate(ConflictKind.SHIFT,
                "Група 4КІ1 навчається в 1 зміну (понеділок: пари 1–4), а СТП стоїть на 5 парі", List.of(LESSON), 1, 5);
        given(publication.publish(VERSION, LocalDate.of(2026, 11, 9), "admin")).willThrow(
                new ConflictException("Версію не можна опублікувати: у ній є жорсткі конфлікти", List.of(shift)));

        mockMvc.perform(post("/api/admin/timetable/versions/{v}/publish", VERSION).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveFrom\":\"2026-11-09\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:schedule-conflict"))
                .andExpect(jsonPath("$.conflicts[0].kind").value("SHIFT"))
                .andExpect(jsonPath("$.conflicts[0].message")
                        .value("Група 4КІ1 навчається в 1 зміну (понеділок: пари 1–4), а СТП стоїть на 5 парі"));
    }

    @Test
    void publicationReportsMaterializedLessonsAndTheAuthor() throws Exception {
        given(publication.publish(VERSION, LocalDate.of(2026, 11, 9), "admin"))
                .willReturn(new PublicationResult(VERSION, LocalDate.of(2026, 11, 9), 9_412, 3, 2, List.of(), List.of()));

        mockMvc.perform(post("/api/admin/timetable/versions/{v}/publish", VERSION).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveFrom\":\"2026-11-09\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.materializedLessons").value(9412))
                .andExpect(jsonPath("$.keptLessons").value(3))
                .andExpect(jsonPath("$.coveredByChanges").value(2))
                .andExpect(jsonPath("$.pendingDecisions").isEmpty())
                .andExpect(jsonPath("$.effectiveFrom").value("2026-11-09"));
        verify(publication).publish(VERSION, LocalDate.of(2026, 11, 9), "admin");
    }

    @Test
    void publicationInThePastIsABusinessRuleViolation() throws Exception {
        given(publication.publish(any(), any(), any())).willThrow(new BusinessRuleException(
                PublicationService.EFFECTIVE_FROM_IN_PAST,
                "Опублікований розклад не можна змінити заднім числом: оберіть дату не раніше 07.11.2026."));

        mockMvc.perform(post("/api/admin/timetable/versions/{v}/publish", VERSION).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"effectiveFrom\":\"2026-10-01\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("EFFECTIVE_FROM_IN_PAST"));
    }

    @Test
    void publishWithoutDateIsRejected() throws Exception {
        mockMvc.perform(post("/api/admin/timetable/versions/{v}/publish", VERSION).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("effectiveFrom"));
    }

    @Test
    void deletingAPublishedVersionIsAConflict() throws Exception {
        willThrow(new ConflictException("Видалити можна лише чернетку: версія «І семестр» уже опублікована."))
                .given(versions).delete(VERSION);

        mockMvc.perform(delete("/api/admin/timetable/versions/{v}", VERSION).with(TestJwts.admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:conflict"));
    }

    @Test
    void diffAndMetricsAreServed() throws Exception {
        UUID other = UUID.randomUUID();
        given(versions.diff(VERSION, other)).willReturn(new VersionDiffDto(List.of(), List.of(), List.of()));
        given(versions.metrics(VERSION)).willReturn(METRICS);

        mockMvc.perform(get("/api/admin/timetable/versions/{a}/diff/{b}", VERSION, other).with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").isArray())
                .andExpect(jsonPath("$.moved").isArray());
        mockMvc.perform(get("/api/admin/timetable/versions/{v}/metrics", VERSION).with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupWindows").value(42))
                .andExpect(jsonPath("$.teacherWindows").value(12));
    }
}
