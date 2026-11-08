package ua.vtc.rozklad.core.timetable.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.shared.error.FieldErrorDto;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;
import ua.vtc.rozklad.core.timetable.internal.importing.ImportTimetableCommand;
import ua.vtc.rozklad.core.timetable.internal.importing.ImportTimetableResult;
import ua.vtc.rozklad.core.timetable.internal.importing.TimetableImportService;
import ua.vtc.rozklad.core.timetable.internal.importing.UnresolvedImportReferencesException;

@WebMvcTest(TimetableImportController.class)
@Import(WebSliceTestConfiguration.class)
class TimetableImportControllerTest {

    private static final UUID SEMESTER = UUID.fromString("0190f0a0-0000-7000-8000-000000000500");
    private static final UUID VERSION = UUID.fromString("0190f0a0-0000-7000-8000-000000000501");
    private static final String BODY = """
            {"semesterId":"%s","name":"Розклад 2025/26 (Excel)","lessons":[
              {"weekday":1,"pair":2,"parity":"BOTH","groupCode":"4КІ1","subgroup":0,"disciplineShortName":"СТП",
               "teacherShortName":"Кренцін М.Д.","roomName":"6","online":false}]}
            """.formatted(SEMESTER);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private TimetableImportService imports;

    @Test
    void importerServiceAccountCreatesADraft() throws Exception {
        given(imports.importTimetable(any(), any())).willReturn(new ImportTimetableResult(VERSION, "Розклад 2025/26 (Excel)",
                1, 0, 0));

        mockMvc.perform(post("/api/admin/import/timetable").with(TestJwts.service())
                        .header("Idempotency-Key", "import-job:42:timetable")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/admin/timetable/versions/" + VERSION))
                .andExpect(jsonPath("$.versionId").value(VERSION.toString()))
                .andExpect(jsonPath("$.lessonCount").value(1));
        verify(imports).importTimetable(new ImportTimetableCommand(SEMESTER, "Розклад 2025/26 (Excel)", List.of(
                new ImportTimetableCommand.Lesson(1, 2, Parity.BOTH, "4КІ1", 0, "СТП", "Кренцін М.Д.", "6", false))),
                "import-job:42:timetable");
    }

    @Test
    void unknownNamesAreListedPerField() throws Exception {
        given(imports.importTimetable(any(), any())).willThrow(new UnresolvedImportReferencesException(List.of(
                new FieldErrorDto("lessons[0].teacherShortName",
                        "Викладача «Іваненко І.І.» не знайдено в каталозі (рядків: 3)"))));

        mockMvc.perform(post("/api/admin/import/timetable").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:business-rule"))
                .andExpect(jsonPath("$.title").value("Порушено правило"))
                .andExpect(jsonPath("$.code").value(UnresolvedImportReferencesException.CODE))
                .andExpect(jsonPath("$.errors[0].field").value("lessons[0].teacherShortName"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Викладача «Іваненко І.І.» не знайдено в каталозі (рядків: 3)"))
                .andExpect(jsonPath("$.requestId").exists());
    }

    @Test
    void invalidLessonIsReportedWithItsIndex() throws Exception {
        mockMvc.perform(post("/api/admin/import/timetable").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("\"pair\":2", "\"pair\":9")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("lessons[0].pair"));
        verify(imports, never()).importTimetable(any(), any());
    }

    @Test
    void teachersCannotImport() throws Exception {
        mockMvc.perform(post("/api/admin/import/timetable").with(TestJwts.teacher())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }
}
