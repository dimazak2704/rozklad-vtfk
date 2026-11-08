package ua.vtc.rozklad.core.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.vtc.rozklad.core.catalog.ApiTestSupport.id;
import static ua.vtc.rozklad.core.catalog.ApiTestSupport.read;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.events.ScheduleChangeReason;
import ua.vtc.rozklad.contracts.events.ScheduleChanged;
import ua.vtc.rozklad.core.catalog.ApiTestSupport;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.support.TestJwts;

/** Calendar API and rules against PostgreSQL: reference data, semesters, parity, periods, bells. */
@CoreIntegrationTest
@RecordApplicationEvents
class CalendarApiIT {

    private static final String SEMESTER_I = """
            {"academicYear":"2026/2027","number":1,"startsOn":"2026-09-01","endsOn":"2026-12-30"}""";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CalendarQueries calendar;

    @Autowired
    private Clock clock;

    @Autowired
    private ApplicationEvents events;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = ApiTestSupport.mockMvc(context);
    }

    @AfterEach
    void cleanUp() {
        ApiTestSupport.cleanDatabase(jdbc);
        jdbc.update("DELETE FROM bell_profile WHERE name LIKE 'Тест%'");
    }

    @Test
    void referenceDataIsSeededByMigrations() throws Exception {
        MvcResult profiles = mvc.perform(get("/api/admin/bell-profiles").with(TestJwts.admin()))
                .andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> defaults = read(profiles, "$[?(@.name == '2 зміни, 60 хв')]");
        assertThat(defaults).singleElement().satisfies(profile -> {
            assertThat(profile).containsEntry("defaultProfile", true).containsEntry("active", true);
            assertThat((List<?>) profile.get("slots")).hasSize(4 * 8 + 6);
        });
        List<String> friday4 = read(profiles,
                "$[?(@.name == '2 зміни, 60 хв')].slots[?(@.weekday == 5 && @.pair == 4)].startsAt");
        assertThat(friday4).containsExactly("12:20");

        mvc.perform(get("/api/admin/shift-windows").with(TestJwts.admin()))
                .andExpect(jsonPath("$", hasSize(10)))
                .andExpect(jsonPath("$[9].shift").value(2))
                .andExpect(jsonPath("$[9].weekday").value(5))
                .andExpect(jsonPath("$[9].firstPair").value(4))
                .andExpect(jsonPath("$[9].lastPair").value(6));
        assertThat(calendar.shiftWindow(1, 3).allows(4)).isTrue();
        assertThat(calendar.shiftWindow(2, 5).allows(7)).isFalse();

        mvc.perform(get("/api/admin/settings").with(TestJwts.admin()))
                .andExpect(jsonPath("$.approvalMode").value("NOTIFY"))
                .andExpect(jsonPath("$.collegeName").value("Вінницький технічний фаховий коледж"))
                .andExpect(jsonPath("$.activeBellProfileId").value((String) defaults.getFirst().get("id")));
    }

    @Test
    void weeksHaveParityAndOverridesWinForOneWeek() throws Exception {
        UUID semester = id(create("/api/admin/semesters", SEMESTER_I)
                .andExpect(jsonPath("$.name").value("I семестр 2026/2027"))
                .andExpect(jsonPath("$.firstWeekParity").value("NUMERATOR"))
                .andExpect(jsonPath("$.teachingWeeks").value(18))
                .andReturn());

        mvc.perform(get("/api/admin/semesters/{id}/weeks", semester).with(TestJwts.admin()))
                .andExpect(jsonPath("$", hasSize(18)))
                .andExpect(jsonPath("$[7].weekStart").value("2026-10-19"))
                .andExpect(jsonPath("$[7].weekNumber").value(8))
                .andExpect(jsonPath("$[7].parity").value("DENOMINATOR"));
        assertThat(calendar.parity(LocalDate.of(2026, 10, 19))).isEqualTo(Parity.DENOMINATOR);

        mvc.perform(put("/api/admin/semesters/{id}/weeks/2026-10-19", semester).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"parity\":\"NUMERATOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parity").value("NUMERATOR"))
                .andExpect(jsonPath("$.computedParity").value("DENOMINATOR"))
                .andExpect(jsonPath("$.overridden").value(true));
        assertThat(calendar.parity(LocalDate.of(2026, 10, 21))).isEqualTo(Parity.NUMERATOR);
        assertThat(calendar.parity(LocalDate.of(2026, 10, 26))).as("наступний тиждень за правилом")
                .isEqualTo(Parity.NUMERATOR);
        assertThat(events.stream(ScheduleChanged.class)).singleElement().satisfies(event -> {
            assertThat(event.reason()).isEqualTo(ScheduleChangeReason.CALENDAR_CHANGED);
            assertThat(event.dates()).containsExactly(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20),
                    LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 23));
        });

        mvc.perform(delete("/api/admin/semesters/{id}/weeks/2026-10-19", semester).with(TestJwts.admin()))
                .andExpect(status().isNoContent());
        assertThat(calendar.week(LocalDate.of(2026, 10, 19))).hasValueSatisfying(week -> {
            assertThat(week.parity()).isEqualTo(Parity.DENOMINATOR);
            assertThat(week.overridden()).isFalse();
        });

        mvc.perform(put("/api/admin/semesters/{id}/weeks/2026-10-20", semester).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"parity\":\"NUMERATOR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("weekStart"));
        mvc.perform(put("/api/admin/semesters/{id}/weeks/2027-02-01", semester).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"parity\":\"NUMERATOR\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyOneSemesterIsActiveAndSemestersDoNotOverlap() throws Exception {
        UUID first = id(create("/api/admin/semesters", SEMESTER_I).andReturn());
        UUID second = id(create("/api/admin/semesters", """
                {"number":2,"startsOn":"2027-01-19","endsOn":"2027-06-30"}""")
                .andExpect(jsonPath("$.academicYear").value("2026/2027"))
                .andReturn());

        mvc.perform(post("/api/admin/semesters/{id}/activate", first).with(TestJwts.admin()))
                .andExpect(jsonPath("$.active").value(true));
        mvc.perform(post("/api/admin/semesters/{id}/activate", second).with(TestJwts.admin()))
                .andExpect(jsonPath("$.active").value(true));
        assertThat(calendar.activeSemester()).map(SemesterInfo::id).contains(second);

        create("/api/admin/semesters", """
                {"academicYear":"2027/2028","number":1,"startsOn":"2027-06-01","endsOn":"2027-12-30"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("перетинаються")));
        create("/api/admin/semesters", SEMESTER_I).andExpect(status().isConflict());
        create("/api/admin/semesters", """
                {"number":1,"startsOn":"2026-12-30","endsOn":"2026-09-01"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("endsOn"));

        mvc.perform(delete("/api/admin/semesters/{id}", second).with(TestJwts.admin()))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/admin/semesters/{id}", first).with(TestJwts.admin()))
                .andExpect(status().isNoContent());
    }

    @Test
    void newFirstWeekParityChangesEveryWeekAndIsAnnounced() throws Exception {
        UUID semester = id(create("/api/admin/semesters", SEMESTER_I).andReturn());

        mvc.perform(put("/api/admin/semesters/{id}", semester).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SEMESTER_I.replace("}", ",\"firstWeekParity\":\"DENOMINATOR\",\"version\":0}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        assertThat(calendar.parity(LocalDate.of(2026, 10, 19))).isEqualTo(Parity.NUMERATOR);
        assertThat(events.stream(ScheduleChanged.class)).singleElement().satisfies(event -> {
            assertThat(event.dates()).first().isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(event.dates()).last().isEqualTo(LocalDate.of(2026, 12, 30));
            assertThat(event.dates()).noneMatch(date -> date.getDayOfWeek().getValue() > 5);
        });

        mvc.perform(put("/api/admin/semesters/{id}", semester).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SEMESTER_I.replace("}", ",\"firstWeekParity\":\"DENOMINATOR\",\"weeksCount\":17}")))
                .andExpect(jsonPath("$.teachingWeeks").value(17));
        assertThat(events.stream(ScheduleChanged.class)).as("кількість тижнів плану розклад не змінює").hasSize(1);
    }

    @Test
    void periodsDecideStudyDaysPerGroup() throws Exception {
        UUID semester = id(create("/api/admin/semesters", SEMESTER_I).andReturn());
        create("/api/admin/specialties", "{\"letters\":\"КІ\",\"name\":\"Комп’ютерна інженерія\"}");
        UUID group = id(create("/api/admin/groups", "{\"code\":\"2КІ3\"}").andReturn());
        UUID other = id(create("/api/admin/groups", "{\"code\":\"4КІ1\"}").andReturn());

        create("/api/admin/calendar-periods", """
                {"semesterId":"%s","kind":"PRACTICE","title":"Електрорадіомонтажна практика",
                 "startsOn":"2026-10-19","endsOn":"2026-10-30","scope":"GROUPS","groupIds":["%s"],
                 "orderNote":"2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р."}"""
                .formatted(semester, group))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groups[0].code").value("2КІ3"));
        create("/api/admin/calendar-periods", """
                {"semesterId":"%s","kind":"STUDY_MODE","studyMode":"ONLINE","title":"4 курс дистанційно",
                 "startsOn":"2026-09-01","endsOn":"2026-10-31","scope":"COURSES","courses":[4]}"""
                .formatted(semester))
                .andExpect(status().isCreated());

        List<ScheduleChanged> announced = events.stream(ScheduleChanged.class).toList();
        assertThat(announced).hasSize(2);
        assertThat(announced.getFirst().groupIds()).containsExactly(group);
        assertThat(announced.getFirst().dates()).hasSize(10).startsWith(LocalDate.of(2026, 10, 19))
                .endsWith(LocalDate.of(2026, 10, 30));
        assertThat(announced.getLast().groupIds()).as("лише групи 4 курсу").containsExactly(other);

        LocalDate monday = LocalDate.of(2026, 10, 19);
        assertThat(calendar.isStudyDay(monday)).isTrue();
        assertThat(calendar.isGroupStudyDay(monday, group)).isFalse();
        assertThat(calendar.isGroupStudyDay(monday, other)).isTrue();
        assertThat(calendar.studyModeFor(monday, other)).isEqualTo(StudyMode.ONLINE);
        assertThat(calendar.studyModeFor(LocalDate.of(2026, 11, 2), other)).isEqualTo(StudyMode.OFFLINE);
        assertThat(calendar.orderNotes(monday))
                .containsExactly("2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р.");

        create("/api/admin/calendar-periods", """
                {"semesterId":"%s","kind":"STUDY_MODE","title":"Без режиму","startsOn":"2026-09-01","endsOn":"2027-01-31"}"""
                .formatted(semester))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("startsOn"))
                .andExpect(jsonPath("$.errors[1].field").value("studyMode"));
        create("/api/admin/calendar-periods", """
                {"semesterId":"%s","kind":"HOLIDAY","title":"Свято","startsOn":"2026-10-14","endsOn":"2026-10-14",
                 "scope":"GROUPS","groupIds":["%s"]}""".formatted(semester, UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("groupIds[0]"));
    }

    @Test
    void publicMetaAndBellsUseTheProfileInEffect() throws Exception {
        LocalDate today = LocalDate.now(clock);
        create("/api/admin/semesters", """
                {"number":1,"startsOn":"%s","endsOn":"%s"}""".formatted(today.minusDays(40), today.plusDays(60)));

        mvc.perform(get("/api/public/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collegeName").value("Вінницький технічний фаховий коледж"))
                .andExpect(jsonPath("$.today").value(today.toString()))
                .andExpect(jsonPath("$.semester.startsOn").value(today.minusDays(40).toString()))
                .andExpect(jsonPath("$.weekNumber").isNumber())
                .andExpect(jsonPath("$.parity").isString())
                .andExpect(jsonPath("$.bellProfile.name").value("2 зміни, 60 хв"));

        LocalDate monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        mvc.perform(get("/api/public/bells").param("date", monday.toString()))
                .andExpect(jsonPath("$.weekday").value(1))
                .andExpect(jsonPath("$.slots", hasSize(8)))
                .andExpect(jsonPath("$.slots[0].startsAt").value("08:30"))
                .andExpect(jsonPath("$.profiles").doesNotExist());
        LocalDate saturday = today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY));
        mvc.perform(get("/api/public/bells").param("date", saturday.toString()))
                .andExpect(jsonPath("$.weekday").value(6))
                .andExpect(jsonPath("$.studyDay").value(false))
                .andExpect(jsonPath("$.slots", hasSize(0)));
        mvc.perform(get("/api/public/bells"))
                .andExpect(jsonPath("$.profiles[?(@.name == '2 зміни, 60 хв')]").exists());
    }

    @Test
    void publicMetaNamesTheNextStudyDayAfterWeekendsAndHolidays() throws Exception {
        LocalDate today = LocalDate.now(clock);
        UUID semester = id(create("/api/admin/semesters", """
                {"number":1,"startsOn":"%s","endsOn":"%s"}""".formatted(today.minusDays(40), today.plusDays(60)))
                .andReturn());
        create("/api/admin/calendar-periods", """
                {"semesterId":"%s","kind":"HOLIDAY","title":"Канікули","startsOn":"%s","endsOn":"%s","scope":"ALL"}"""
                .formatted(semester, today.plusDays(1), today.plusDays(7)))
                .andExpect(status().isCreated());
        LocalDate expected = today.plusDays(8);
        while (expected.getDayOfWeek().getValue() > 5) {
            expected = expected.plusDays(1);
        }

        mvc.perform(get("/api/public/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextStudyDay.date").value(expected.toString()))
                .andExpect(jsonPath("$.nextStudyDay.weekday").value(expected.getDayOfWeek().getValue()))
                .andExpect(jsonPath("$.nextStudyDay.weekNumber").value(calendar.weekNumber(expected)))
                .andExpect(jsonPath("$.nextStudyDay.parity").value(calendar.parity(expected).name()));
    }

    @Test
    void publicMetaHasNoNextStudyDayWithoutASemesterAhead() throws Exception {
        LocalDate today = LocalDate.now(clock);
        create("/api/admin/semesters", """
                {"number":1,"startsOn":"%s","endsOn":"%s"}""".formatted(today.minusDays(60), today));

        mvc.perform(get("/api/public/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextStudyDay").doesNotExist());
    }

    @Test
    void bellProfilesAreValidatedAndProtected() throws Exception {
        create("/api/admin/bell-profiles", """
                {"name":"Тест накладка","slots":[
                  {"weekday":1,"pair":1,"startsAt":"08:30","endsAt":"09:30"},
                  {"weekday":1,"pair":2,"startsAt":"09:00","endsAt":"10:00"},
                  {"weekday":1,"pair":2,"startsAt":"10:10","endsAt":"10:05"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", org.hamcrest.Matchers.hasItems("slots[2].endsAt", "slots[2].pair")));

        UUID profile = id(create("/api/admin/bell-profiles", """
                {"name":"Тест 1 зміна","slots":[{"weekday":1,"pair":1,"startsAt":"08:30","endsAt":"09:50"}]}""")
                .andExpect(status().isCreated())
                .andReturn());
        UUID semester = id(create("/api/admin/semesters", SEMESTER_I.replace("}", ",\"bellProfileId\":\"" + profile + "\"}"))
                .andReturn());
        mvc.perform(delete("/api/admin/bell-profiles/{id}", profile).with(TestJwts.admin()))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/admin/semesters/{id}", semester).with(TestJwts.admin()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/admin/bell-profiles/{id}", profile).with(TestJwts.admin()))
                .andExpect(status().isNoContent());

        String defaultId = read(mvc.perform(get("/api/admin/settings").with(TestJwts.admin())).andReturn(),
                "$.activeBellProfileId");
        mvc.perform(delete("/api/admin/bell-profiles/{id}", defaultId).with(TestJwts.admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("типовий")));
    }

    @Test
    void snapshotIsForServicesOnly() throws Exception {
        create("/api/admin/semesters", SEMESTER_I);

        mvc.perform(get("/api/internal/catalog/snapshot").with(TestJwts.service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups").isArray())
                .andExpect(jsonPath("$.semesters[0].name").value("I семестр 2026/2027"));
        mvc.perform(get("/api/internal/catalog/snapshot").with(TestJwts.admin())).andExpect(status().isForbidden());
    }

    private ResultActions create(String path, String json) throws Exception {
        return mvc.perform(post(path).with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
