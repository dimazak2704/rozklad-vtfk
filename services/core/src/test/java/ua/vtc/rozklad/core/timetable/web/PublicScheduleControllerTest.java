package ua.vtc.rozklad.core.timetable.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
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
import ua.vtc.rozklad.core.settings.SettingsService;
import ua.vtc.rozklad.core.settings.internal.PublicHistoryTestConfiguration;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.support.TestClock;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.support.WebSliceTestConfiguration;
import ua.vtc.rozklad.core.timetable.LessonView;
import ua.vtc.rozklad.core.timetable.LessonViewKind;
import ua.vtc.rozklad.core.timetable.ScheduleOwnerType;
import ua.vtc.rozklad.core.timetable.ScheduleQueries;
import ua.vtc.rozklad.core.timetable.ScheduleView;
import ua.vtc.rozklad.core.timetable.internal.schedule.IcsExportService;

@WebMvcTest(PublicScheduleController.class)
@Import({WebSliceTestConfiguration.class, PublicScheduleCachingConfig.class, TestClock.Configuration.class,
        PublicHistoryTestConfiguration.class})
class PublicScheduleControllerTest {

    private static final UUID GROUP = UUID.fromString("0190f0a0-0000-7000-8000-000000000300");
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);
    /** Wednesday 21.10.2026, 10:00 in Kyiv: 7 days of history start on 14.10, so visitors' weeks start on 12.10. */
    private static final Instant NOW = Instant.parse("2026-10-21T07:00:00Z");
    private static final LocalDate WINDOW_MONDAY = LocalDate.of(2026, 10, 12);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestClock clock;

    @MockitoBean
    private SettingsService settings;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private ScheduleQueries schedules;

    @MockitoBean
    private IcsExportService icsExport;

    @BeforeEach
    void setUp() {
        clock.stopAt(NOW);
        given(settings.changesHistoryDays()).willReturn(7);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void scheduleIsPublicCacheableAndRevalidatedWithEtag() throws Exception {
        given(schedules.schedule(ScheduleOwnerType.GROUP, GROUP, MONDAY, MONDAY)).willReturn(schedule());

        MvcResult first = mockMvc.perform(get("/api/public/schedule/group/{id}", GROUP)
                        .param("from", "2026-10-19").param("to", "2026-10-19"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, public"))
                .andExpect(header().string(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.owner.label").value("4КІ1"))
                .andExpect(jsonPath("$.days[0].weekdayLabel").value("ПОНЕДІЛОК"))
                .andExpect(jsonPath("$.days[0].parity").value("DENOMINATOR"))
                .andExpect(jsonPath("$.days[0].lessons[0].startsAt").value("09:40"))
                .andExpect(jsonPath("$.days[0].lessons[0].kind").value("SUBSTITUTED"))
                .andExpect(jsonPath("$.days[0].lessons[0].original.teacher.shortName").value("Кренцін М.Д."))
                .andExpect(jsonPath("$.days[0].lessons[0].reasonText").value("Зв.зам."))
                .andExpect(jsonPath("$.days[0].lessons[0].parity").value(nullValue()))
                .andExpect(jsonPath("$.days[0].lessons[1].kind").value("REGULAR"))
                .andExpect(jsonPath("$.days[0].lessons[1].parity").value("DENOMINATOR"))
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
    void visitorMayStartAtTheMondayOfTheWeekWhereTheWindowStarts() throws Exception {
        given(schedules.schedule(eq(ScheduleOwnerType.GROUP), eq(GROUP), any(), any())).willReturn(schedule());

        mockMvc.perform(get("/api/public/schedule/group/{id}", GROUP)
                        .param("from", "2026-10-12").param("to", "2026-10-18"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, public"));
        mockMvc.perform(get("/api/public/schedule/group/{id}", GROUP)
                        .param("from", "2027-02-01").param("to", "2027-02-07"))
                .andExpect(status().isOk());
    }

    @Test
    void visitorRangeStartingBeforeThatMondayIsHistoryNotPublic() throws Exception {
        mockMvc.perform(get("/api/public/schedule/group/{id}", GROUP)
                        .param("from", "2026-10-11").param("to", "2026-10-17"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:history-not-public"))
                .andExpect(jsonPath("$.code").value("HISTORY_NOT_PUBLIC"))
                .andExpect(jsonPath("$.publicHistoryFrom").value("2026-10-14"))
                .andExpect(jsonPath("$.detail")
                        .value("Розклад із замінами за тижні до 12.10.2026 бачить лише персонал коледжу."));
        mockMvc.perform(get("/api/public/schedule/teacher/{id}", GROUP)
                        .param("from", "2026-10-05").param("to", "2026-10-25"))
                .andExpect(status().isNotFound());
        verify(schedules, never()).schedule(any(), any(), any(), any());
    }

    /** Staff answers are private: a shared cache must not hand an old week to a visitor. */
    @Test
    void staffGetOldWeeksAsAPrivateAnswer() throws Exception {
        LocalDate september = LocalDate.of(2025, 9, 1);
        given(schedules.schedule(ScheduleOwnerType.GROUP, GROUP, september, september.plusDays(6)))
                .willReturn(schedule());

        mockMvc.perform(get("/api/public/schedule/group/{id}", GROUP).with(TestJwts.teacher())
                        .param("from", "2025-09-01").param("to", "2025-09-07"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"))
                .andExpect(header().string(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION));
    }

    @Test
    void forgedOrExpiredTokenIs401() throws Exception {
        given(jwtDecoder.decode(anyString())).willThrow(new BadJwtException("expired"));

        mockMvc.perform(get("/api/public/schedule/group/{id}", GROUP).header(HttpHeaders.AUTHORIZATION, "Bearer x")
                        .param("from", "2025-09-01").param("to", "2025-09-07"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/public/ics/group/{id}.ics", GROUP).header(HttpHeaders.AUTHORIZATION, "Bearer x"))
                .andExpect(status().isUnauthorized());
        verify(schedules, never()).schedule(any(), any(), any(), any());
        verify(icsExport, never()).export(any(), any(), any());
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

    /** A visitor's calendar starts on the Monday of the window's week; staff get the whole semester. */
    @Test
    void icsIsServedAsTextCalendar() throws Exception {
        given(icsExport.export(eq(ScheduleOwnerType.GROUP), eq(GROUP), any())).willReturn(new IcsExportService.IcsFile(
                "rozklad-" + GROUP + ".ics", "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n"));

        mockMvc.perform(get("/api/public/ics/group/{id}.ics", GROUP))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, public"))
                .andExpect(header().string(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/calendar;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"rozklad-" + GROUP + ".ics\""))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(content().string("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n"));
        verify(icsExport).export(ScheduleOwnerType.GROUP, GROUP, WINDOW_MONDAY);

        mockMvc.perform(get("/api/public/ics/group/{id}.ics", GROUP).with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, private"));
        verify(icsExport).export(eq(ScheduleOwnerType.GROUP), eq(GROUP), isNull());
    }

    @Test
    void roomsHaveNoCalendarSubscription() throws Exception {
        mockMvc.perform(get("/api/public/ics/room/{id}.ics", GROUP)).andExpect(status().isNotFound());
        verify(icsExport, never()).export(any(), any(), any());
    }

    private static ScheduleView schedule() {
        LessonView lesson = new LessonView(UUID.randomUUID(), 2, LocalTime.of(9, 40), LocalTime.of(10, 40),
                new DisciplineRef(UUID.randomUUID(), "НДЕКСМ", "Надійність, діагностика та експлуатація", null),
                new TeacherRef(UUID.randomUUID(), "Непийвода М.В.", "Непийвода"),
                new RoomRef(UUID.randomUUID(), "6", RoomType.COMPUTER_LAB), new GroupRef(GROUP, "4КІ1", 4), 0,
                null, LessonViewKind.SUBSTITUTED, false,
                new LessonView.Original(new DisciplineRef(UUID.randomUUID(), "СТП", "Сучасні технології", null),
                        new TeacherRef(UUID.randomUUID(), "Кренцін М.Д.", "Кренцін"), null, null),
                "Зв.зам.", UUID.randomUUID());
        // пара лише по знаменниках (як і сам день): парність рядка шаблону
        LessonView denominatorOnly = new LessonView(UUID.randomUUID(), 3, LocalTime.of(11, 0), LocalTime.of(12, 0),
                new DisciplineRef(UUID.randomUUID(), "СТП", "Сучасні технології", null), null, null,
                new GroupRef(GROUP, "4КІ1", 4), 0, Parity.DENOMINATOR, LessonViewKind.REGULAR, false, null, null, null);
        return new ScheduleView(new ScheduleView.Owner(ScheduleOwnerType.GROUP, GROUP, "4КІ1"), MONDAY, MONDAY,
                List.of(new ScheduleView.Day(MONDAY, 1, "ПОНЕДІЛОК", Parity.DENOMINATOR, true, null,
                        List.of(lesson, denominatorOnly))));
    }
}
