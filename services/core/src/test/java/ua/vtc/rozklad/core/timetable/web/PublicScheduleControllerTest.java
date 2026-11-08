package ua.vtc.rozklad.core.timetable.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.RoomType;
import ua.vtc.rozklad.core.catalog.DisciplineRef;
import ua.vtc.rozklad.core.catalog.GroupRef;
import ua.vtc.rozklad.core.catalog.RoomRef;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;
import ua.vtc.rozklad.core.timetable.LessonView;
import ua.vtc.rozklad.core.timetable.LessonViewKind;
import ua.vtc.rozklad.core.timetable.ScheduleOwnerType;
import ua.vtc.rozklad.core.timetable.ScheduleQueries;
import ua.vtc.rozklad.core.timetable.ScheduleView;
import ua.vtc.rozklad.core.timetable.internal.schedule.IcsExportService;

@WebMvcTest(PublicScheduleController.class)
@Import({WebSliceTestConfiguration.class, PublicScheduleCachingConfig.class})
class PublicScheduleControllerTest {

    private static final UUID GROUP = UUID.fromString("0190f0a0-0000-7000-8000-000000000300");
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private ScheduleQueries schedules;

    @MockitoBean
    private IcsExportService icsExport;

    @Test
    void scheduleIsPublicCacheableAndRevalidatedWithEtag() throws Exception {
        given(schedules.schedule(ScheduleOwnerType.GROUP, GROUP, MONDAY, MONDAY)).willReturn(schedule());

        MvcResult first = mockMvc.perform(get("/api/public/schedule/group/{id}", GROUP)
                        .param("from", "2026-10-19").param("to", "2026-10-19"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, public"))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.owner.label").value("4КІ1"))
                .andExpect(jsonPath("$.days[0].weekdayLabel").value("ПОНЕДІЛОК"))
                .andExpect(jsonPath("$.days[0].parity").value("DENOMINATOR"))
                .andExpect(jsonPath("$.days[0].lessons[0].startsAt").value("09:40"))
                .andExpect(jsonPath("$.days[0].lessons[0].kind").value("SUBSTITUTED"))
                .andExpect(jsonPath("$.days[0].lessons[0].original.teacher.shortName").value("Кренцін М.Д."))
                .andExpect(jsonPath("$.days[0].lessons[0].reasonText").value("Зв.зам."))
                .andReturn();

        mockMvc.perform(get("/api/public/schedule/group/{id}", GROUP)
                        .param("from", "2026-10-19").param("to", "2026-10-19")
                        .header(HttpHeaders.IF_NONE_MATCH, first.getResponse().getHeader(HttpHeaders.ETAG)))
                .andExpect(status().isNotModified());
    }

    @Test
    void withoutDatesTheCurrentWeekIsReturned() throws Exception {
        given(schedules.schedule(eq(ScheduleOwnerType.TEACHER), eq(GROUP), any(), any())).willReturn(schedule());

        mockMvc.perform(get("/api/public/schedule/teacher/{id}", GROUP)).andExpect(status().isOk());

        ArgumentCaptor<LocalDate> from = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> to = ArgumentCaptor.forClass(LocalDate.class);
        verify(schedules).schedule(eq(ScheduleOwnerType.TEACHER), eq(GROUP), from.capture(), to.capture());
        assertThat(from.getValue().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(ChronoUnit.DAYS.between(from.getValue(), to.getValue())).isEqualTo(6);
    }

    @Test
    void rangeLongerThan31DaysIsRejected() throws Exception {
        mockMvc.perform(get("/api/public/schedule/room/{id}", GROUP).param("from", "2026-10-01")
                        .param("to", "2026-11-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("to"))
                .andExpect(jsonPath("$.errors[0].message").value("Можна запитати не більше 31 днів за раз"));
        mockMvc.perform(get("/api/public/schedule/room/{id}", GROUP).param("from", "2026-10-20")
                        .param("to", "2026-10-19"))
                .andExpect(status().isBadRequest());
        verify(schedules, never()).schedule(any(), any(), any(), any());
    }

    @Test
    void unknownOwnerTypeOrOwnerIsNotFound() throws Exception {
        mockMvc.perform(get("/api/public/schedule/building/{id}", GROUP)).andExpect(status().isNotFound());
        given(schedules.schedule(eq(ScheduleOwnerType.GROUP), eq(GROUP), any(), any()))
                .willThrow(NotFoundException.of("Групу", GROUP));
        mockMvc.perform(get("/api/public/schedule/group/{id}", GROUP))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Групу з ідентифікатором " + GROUP + " не знайдено"));
        mockMvc.perform(get("/api/public/schedule/group/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void icsIsServedAsTextCalendar() throws Exception {
        given(icsExport.export(ScheduleOwnerType.GROUP, GROUP)).willReturn(new IcsExportService.IcsFile(
                "rozklad-" + GROUP + ".ics", "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n"));

        mockMvc.perform(get("/api/public/ics/group/{id}.ics", GROUP))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/calendar;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"rozklad-" + GROUP + ".ics\""))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(content().string("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n"));
    }

    @Test
    void roomsHaveNoCalendarSubscription() throws Exception {
        mockMvc.perform(get("/api/public/ics/room/{id}.ics", GROUP)).andExpect(status().isNotFound());
        verify(icsExport, never()).export(any(), any());
    }

    private static ScheduleView schedule() {
        LessonView lesson = new LessonView(UUID.randomUUID(), 2, LocalTime.of(9, 40), LocalTime.of(10, 40),
                new DisciplineRef(UUID.randomUUID(), "НДЕКСМ", "Надійність, діагностика та експлуатація", null),
                new TeacherRef(UUID.randomUUID(), "Непийвода М.В.", "Непийвода"),
                new RoomRef(UUID.randomUUID(), "6", RoomType.COMPUTER_LAB), new GroupRef(GROUP, "4КІ1", 4), 0,
                LessonViewKind.SUBSTITUTED, false,
                new LessonView.Original(new DisciplineRef(UUID.randomUUID(), "СТП", "Сучасні технології", null),
                        new TeacherRef(UUID.randomUUID(), "Кренцін М.Д.", "Кренцін"), null, null),
                "Зв.зам.", UUID.randomUUID());
        return new ScheduleView(new ScheduleView.Owner(ScheduleOwnerType.GROUP, GROUP, "4КІ1"), MONDAY, MONDAY,
                List.of(new ScheduleView.Day(MONDAY, 1, "ПОНЕДІЛОК", Parity.DENOMINATOR, true, null,
                        List.of(lesson))));
    }
}
