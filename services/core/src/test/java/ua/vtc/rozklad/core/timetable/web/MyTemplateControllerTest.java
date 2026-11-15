package ua.vtc.rozklad.core.timetable.web;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.ErrorResponseException;
import ua.vtc.rozklad.contracts.LessonKind;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.catalog.DisciplineRef;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.identity.CurrentUserService;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;
import ua.vtc.rozklad.core.timetable.TimetableVersion;
import ua.vtc.rozklad.core.timetable.TimetableVersionRepository;
import ua.vtc.rozklad.core.timetable.internal.version.TemplateLessonDto;
import ua.vtc.rozklad.core.timetable.internal.version.TemplateLessonService;

@WebMvcTest(MyTemplateController.class)
@Import(WebSliceTestConfiguration.class)
class MyTemplateControllerTest {

    private static final UUID KRENTSIN = UUID.fromString("0190f0a0-0000-7000-8000-000000000401");
    private static final UUID SEMESTER = UUID.fromString("0190f0a0-0000-7000-8000-000000000501");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private TimetableVersionRepository versions;

    @MockitoBean
    private TemplateLessonService lessons;

    @MockitoBean
    private CurrentUserService currentUser;

    @Test
    void teacherGetsOwnSlotsOfThePublishedVersion() throws Exception {
        TimetableVersion published = org.mockito.Mockito.mock(TimetableVersion.class);
        UUID versionId = UUID.randomUUID();
        given(published.getId()).willReturn(versionId);
        given(currentUser.requireTeacherId()).willReturn(KRENTSIN);
        given(versions.findPublished(SEMESTER)).willReturn(Optional.of(published));
        given(lessons.list(versionId, null, KRENTSIN, null)).willReturn(List.of(new TemplateLessonDto(UUID.randomUUID(),
                2, 3, Parity.BOTH, new GroupRef(UUID.randomUUID(), "4КІ1", 4), 0,
                new DisciplineRef(UUID.randomUUID(), "СТП", "Сучасні технології проєктування", null), null, null,
                LessonKind.MIXED, null, false, false, null, 0)));

        mockMvc.perform(get("/api/me/template").param("semesterId", SEMESTER.toString()).with(TestJwts.teacher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].group.code").value("4КІ1"))
                .andExpect(jsonPath("$[0].weekday").value(2));
    }

    @Test
    void noPublishedVersionMeansNoSlots() throws Exception {
        given(currentUser.requireTeacherId()).willReturn(KRENTSIN);
        given(versions.findPublished(SEMESTER)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/me/template").param("semesterId", SEMESTER.toString()).with(TestJwts.teacher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        verifyNoInteractions(lessons);
    }

    @Test
    void accountWithoutATeacherIsForbidden() throws Exception {
        given(currentUser.requireTeacherId()).willThrow(new ErrorResponseException(HttpStatus.FORBIDDEN));

        mockMvc.perform(get("/api/me/template").param("semesterId", SEMESTER.toString()).with(TestJwts.admin()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(versions, lessons);
    }

    @Test
    void anonymousCallerIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/me/template").param("semesterId", SEMESTER.toString()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(versions, lessons);
    }
}
