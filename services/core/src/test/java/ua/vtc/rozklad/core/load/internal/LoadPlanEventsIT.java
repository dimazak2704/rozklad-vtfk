package ua.vtc.rozklad.core.load.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.vtc.rozklad.core.catalog.ApiTestSupport.id;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.ApplicationListenerMethodAdapter;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import ua.vtc.rozklad.core.calendar.CalendarEdited;
import ua.vtc.rozklad.core.calendar.StudyCalendar;
import ua.vtc.rozklad.core.catalog.ApiTestSupport;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.support.TestJwts;

/** When AUTO plans are recounted: at start-up, and on a calendar edit after the timetable has locked the semester. */
@CoreIntegrationTest
class LoadPlanEventsIT {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LoadPlanEvents events;

    @Autowired
    private ApplicationEventMulticaster multicaster;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = ApiTestSupport.mockMvc(context);
    }

    @AfterEach
    void cleanUp() {
        ApiTestSupport.cleanDatabase(jdbc);
    }

    /** A plan stored by the old rule (3 × 18 = 54) is recounted at start-up; a set plan is not. */
    @Test
    void startUpRecountsStoredAutoPlansAndKeepsSetOnes() throws Exception {
        UUID semester = id(create("/api/admin/semesters",
                "{\"number\":1,\"startsOn\":\"2026-09-01\",\"endsOn\":\"2026-12-30\"}").andReturn());
        create("/api/admin/specialties", "{\"letters\":\"КІ\",\"name\":\"Комп’ютерна інженерія\"}");
        UUID group = id(create("/api/admin/groups", "{\"code\":\"4КІ2\"}").andReturn());
        UUID dk = id(create("/api/admin/disciplines", "{\"name\":\"Державна мова\",\"shortName\":\"ДК\"}").andReturn());
        UUID english = id(create("/api/admin/disciplines", "{\"name\":\"Іноземна мова\",\"shortName\":\"Ін.мова\"}")
                .andReturn());
        UUID auto = id(create("/api/admin/load", """
                {"semesterId":"%s","groupId":"%s","disciplineId":"%s","hoursPerWeek":3}""".formatted(semester, group, dk))
                .andReturn());
        UUID fixed = id(create("/api/admin/load", """
                {"semesterId":"%s","groupId":"%s","disciplineId":"%s","hoursPerWeek":2,"plannedHours":40}"""
                .formatted(semester, group, english)).andReturn());
        jdbc.update("UPDATE load_item SET planned_hours = 54 WHERE id = ?", auto);
        jdbc.update("UPDATE load_item SET planned_hours = 36 WHERE id = ?", fixed);

        events.recountOnStart();

        // без розкладу й періодів: 3 год × 87 навчальних днів / 5 = 52
        expectPlan(auto, 52, "AUTO");
        expectPlan(fixed, 36, "FIXED");
    }

    /**
     * A publication holds the semester's publication lock and then, through its event, takes the load lock. A calendar
     * edit must take them in the same order — the timetable's listener (publication locks) before the plan recount
     * (load lock) — or an edit and a publication of one semester can deadlock.
     */
    @Test
    void theTimetableHandlesACalendarEditBeforeThePlansAreRecounted() {
        CalendarEdited edit = new CalendarEdited(List.of(LocalDate.of(2026, 11, 30)), List.of(UUID.randomUUID()),
                new StudyCalendar(List.of()));
        Collection<ApplicationListener<?>> listeners = ReflectionTestUtils.invokeMethod(multicaster,
                "getApplicationListeners", new PayloadApplicationEvent<>(this, edit),
                ResolvableType.forClassWithGenerics(PayloadApplicationEvent.class, CalendarEdited.class));

        assertThat(listeners).isNotNull();
        List<String> order = listeners.stream()
                .filter(ApplicationListenerMethodAdapter.class::isInstance)
                .map(listener -> ((ApplicationListenerMethodAdapter) listener).getListenerId())
                .map(id -> id.substring(0, id.indexOf('(')))
                .toList();
        assertThat(order).containsSubsequence(
                "ua.vtc.rozklad.core.timetable.internal.publication.CalendarChangeListener.on",
                "ua.vtc.rozklad.core.load.internal.LoadPlanEvents.on");
    }

    private void expectPlan(UUID item, int hours, String source) throws Exception {
        mvc.perform(get("/api/admin/load/{id}", item).with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plannedHours").value(hours))
                .andExpect(jsonPath("$.planSource").value(source));
    }

    private ResultActions create(String path, String json) throws Exception {
        return mvc.perform(post(path).with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());
    }
}
