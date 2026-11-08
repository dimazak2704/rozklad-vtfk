package ua.vtc.rozklad.core.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.vtc.rozklad.core.catalog.ApiTestSupport.id;
import static ua.vtc.rozklad.core.catalog.ApiTestSupport.read;

import java.time.LocalDate;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import ua.vtc.rozklad.core.catalog.ApiTestSupport;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.support.TestJwts;

/**
 * Дзвінки й дати календаря не зсуваються, коли JVM працює в часовому поясі коледжу (Europe/Kyiv),
 * а Hibernate пише з {@code jdbc.time_zone=UTC}: тести решти модулів ідуть в UTC і цього не бачать.
 */
@CoreIntegrationTest
class CalendarTimeZoneIT {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CalendarQueries calendar;

    private MockMvc mvc;
    private TimeZone original;

    @BeforeEach
    void setUp() {
        mvc = ApiTestSupport.mockMvc(context);
        original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Kyiv"));
    }

    @AfterEach
    void cleanUp() {
        TimeZone.setDefault(original);
        ApiTestSupport.cleanDatabase(jdbc);
        jdbc.update("DELETE FROM bell_profile WHERE name LIKE 'Тест%'");
    }

    @Test
    void bellTimesKeepTheirWallClockValue() throws Exception {
        List<String> friday = read(mvc.perform(get("/api/admin/bell-profiles").with(TestJwts.admin())).andReturn(),
                "$[?(@.name == '2 зміни, 60 хв')].slots[?(@.weekday == 5)].startsAt");
        assertThat(friday).containsExactly("08:30", "09:40", "11:00", "12:20", "13:30", "14:50");

        UUID profile = id(create("/api/admin/bell-profiles", """
                {"name":"Тест пояс","slots":[{"weekday":1,"pair":1,"startsAt":"08:30","endsAt":"09:30"}]}""")
                .andExpect(status().isCreated())
                .andReturn());
        assertThat(jdbc.queryForObject("SELECT starts_at::text FROM bell_slot WHERE profile_id = ?", String.class, profile))
                .isEqualTo("08:30:00");
        mvc.perform(get("/api/admin/bell-profiles/{id}", profile).with(TestJwts.admin()))
                .andExpect(jsonPath("$.slots[0].startsAt").value("08:30"))
                .andExpect(jsonPath("$.slots[0].endsAt").value("09:30"));
    }

    @Test
    void calendarDatesKeepTheirDay() throws Exception {
        UUID semester = id(create("/api/admin/semesters", """
                {"academicYear":"2026/2027","number":1,"startsOn":"2026-09-01","endsOn":"2026-12-30"}""")
                .andExpect(jsonPath("$.startsOn").value("2026-09-01"))
                .andReturn());
        assertThat(jdbc.queryForObject("SELECT starts_on::text || ' ' || ends_on::text FROM semester WHERE id = ?",
                String.class, semester)).isEqualTo("2026-09-01 2026-12-30");

        mvc.perform(put("/api/admin/semesters/{id}/weeks/2026-10-19", semester).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"parity\":\"NUMERATOR\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT week_start::text FROM week_parity_override WHERE semester_id = ?",
                String.class, semester)).isEqualTo("2026-10-19");

        create("/api/admin/calendar-periods", """
                {"semesterId":"%s","kind":"HOLIDAY","title":"Тест свято","startsOn":"2026-10-14","endsOn":"2026-10-14"}"""
                .formatted(semester))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startsOn").value("2026-10-14"));
        assertThat(jdbc.queryForObject("SELECT starts_on::text FROM calendar_period WHERE semester_id = ?",
                String.class, semester)).isEqualTo("2026-10-14");
        assertThat(calendar.isStudyDay(LocalDate.of(2026, 10, 14))).isFalse();
        assertThat(calendar.isStudyDay(LocalDate.of(2026, 10, 13))).isTrue();
        assertThat(calendar.weekNumber(LocalDate.of(2026, 9, 1))).isEqualTo(1);
    }

    private ResultActions create(String path, String json) throws Exception {
        return mvc.perform(post(path).with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
