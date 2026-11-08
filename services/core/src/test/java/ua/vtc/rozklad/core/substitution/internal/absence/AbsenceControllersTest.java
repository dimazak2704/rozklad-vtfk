package ua.vtc.rozklad.core.substitution.internal.absence;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ua.vtc.rozklad.contracts.AbsenceReasonCategory;
import ua.vtc.rozklad.contracts.AbsenceSource;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.substitution.AbsenceStatus;
import ua.vtc.rozklad.core.substitution.internal.support.CurrentTeacher;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;

/** Absences of the dispatcher, of the teacher and from the bot: roles, validation, ownership. */
@WebMvcTest({AdminAbsenceController.class, MyAbsenceController.class, InternalAbsenceController.class})
@Import(WebSliceTestConfiguration.class)
class AbsenceControllersTest {

    private static final UUID TEACHER_ID = UUID.randomUUID();
    private static final UUID ABSENCE_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AbsenceService absences;

    @MockitoBean
    private AffectedLessons affectedLessons;

    @MockitoBean
    private CurrentTeacher currentTeacher;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void dispatcherReportsAnAbsence() throws Exception {
        given(absences.report(eq(TEACHER_ID), any(), eq(AbsenceSource.ADMIN), eq("admin"))).willReturn(sample());

        mockMvc.perform(post("/api/admin/absences").with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("2026-10-19", "2026-10-21", "[2,3]")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/admin/absences/" + ABSENCE_ID)))
                .andExpect(jsonPath("$.reasonCategory").value("SICK_LEAVE"))
                .andExpect(jsonPath("$.pairs").value(nullValue()));
    }

    @Test
    void endBeforeStartIsReportedOnTheEndDate() throws Exception {
        mockMvc.perform(post("/api/admin/absences").with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("2026-10-21", "2026-10-19", "null")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("dateTo"))
                .andExpect(jsonPath("$.errors[0].message").value("Дата завершення не може бути раніше за дату початку"));
        verifyNoInteractions(absences);
    }

    @Test
    void pairsAreOneToEight() throws Exception {
        mockMvc.perform(post("/api/admin/absences").with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("2026-10-19", "2026-10-19", "[9]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("pairs[0]"));
    }

    @Test
    void absenceLongerThanAYearIsRejected() throws Exception {
        mockMvc.perform(post("/api/admin/absences").with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("2026-01-01", "2027-06-01", "null")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Відсутність не може тривати понад рік"));
    }

    @Test
    void teacherCannotUseTheDispatcherApi() throws Exception {
        mockMvc.perform(get("/api/admin/absences").with(TestJwts.teacher())).andExpect(status().isForbidden());
    }

    @Test
    void affectedLessonsOfAnAbsence() throws Exception {
        given(affectedLessons.of(ABSENCE_ID)).willReturn(List.of());

        mockMvc.perform(get("/api/admin/absences/{id}/affected", ABSENCE_ID).with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void teacherReportsOwnAbsenceFromTheCabinet() throws Exception {
        Teacher krentsin = new Teacher("Кренцін", "Кренцін М.Д.", "Кренцін");
        given(currentTeacher.require(any())).willReturn(krentsin);
        given(absences.report(eq(krentsin.getId()), any(), eq(AbsenceSource.WEB), eq("krentsin"))).willReturn(sample());

        mockMvc.perform(post("/api/me/absences").with(TestJwts.teacher()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dateFrom\":\"2026-11-10\",\"dateTo\":\"2026-11-10\",\"reasonCategory\":\"PERSONAL\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void teacherCannotCancelSomeoneElsesAbsence() throws Exception {
        Teacher krentsin = new Teacher("Кренцін", "Кренцін М.Д.", "Кренцін");
        given(currentTeacher.require(any())).willReturn(krentsin);
        willThrow(new AccessDeniedException("Це відсутність іншого викладача"))
                .given(absences).cancelOwn(ABSENCE_ID, krentsin.getId(), "krentsin");

        mockMvc.perform(delete("/api/me/absences/{id}", ABSENCE_ID).with(TestJwts.teacher()))
                .andExpect(status().isForbidden());
    }

    @Test
    void startedAbsenceCannotBeCancelledByTheTeacher() throws Exception {
        Teacher krentsin = new Teacher("Кренцін", "Кренцін М.Д.", "Кренцін");
        given(currentTeacher.require(any())).willReturn(krentsin);
        willThrow(new BusinessRuleException("ABSENCE_STARTED", "Скасувати можна лише майбутню"))
                .given(absences).cancelOwn(ABSENCE_ID, krentsin.getId(), "krentsin");

        mockMvc.perform(delete("/api/me/absences/{id}", ABSENCE_ID).with(TestJwts.teacher()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ABSENCE_STARTED"));
    }

    @Test
    void botReportsWithSourceBot() throws Exception {
        given(absences.report(eq(TEACHER_ID), any(), eq(AbsenceSource.BOT), any())).willReturn(sample());

        mockMvc.perform(post("/api/internal/absences").with(TestJwts.service()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("2026-11-10", "2026-11-10", "null").replace("}", ",\"source\":\"BOT\"}")))
                .andExpect(status().isCreated());
        verify(absences).report(eq(TEACHER_ID), any(), eq(AbsenceSource.BOT), eq(
                "service-account-rozklad-notification"));
    }

    @Test
    void botCannotClaimAnotherSource() throws Exception {
        mockMvc.perform(post("/api/internal/absences").with(TestJwts.service()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("2026-11-10", "2026-11-10", "null").replace("}", ",\"source\":\"WEB\"}")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ABSENCE_SOURCE"));
    }

    @Test
    void teacherCannotUseTheInternalApi() throws Exception {
        mockMvc.perform(post("/api/internal/absences").with(TestJwts.teacher()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("2026-11-10", "2026-11-10", "null")))
                .andExpect(status().isForbidden());
    }

    private static String body(String from, String to, String pairs) {
        return "{\"teacherId\":\"%s\",\"dateFrom\":\"%s\",\"dateTo\":\"%s\",\"pairs\":%s,\"reasonCategory\":\"SICK_LEAVE\"}"
                .formatted(TEACHER_ID, from, to, pairs);
    }

    private static AbsenceDto sample() {
        return new AbsenceDto(ABSENCE_ID, new TeacherRef(TEACHER_ID, "Крисак Л.В.", "Крисак Л."),
                LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 21), null, AbsenceReasonCategory.SICK_LEAVE, null,
                AbsenceSource.ADMIN, AbsenceStatus.ACTIVE, "admin", Instant.parse("2026-10-18T10:00:00Z"), 0);
    }
}
