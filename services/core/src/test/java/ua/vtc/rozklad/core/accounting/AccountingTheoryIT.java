package ua.vtc.rozklad.core.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.vtc.rozklad.core.catalog.ApiTestSupport.id;

import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.core.accounting.internal.AccountingTheory;
import ua.vtc.rozklad.core.catalog.ApiTestSupport;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.support.TestClock;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.timetable.TemplateLesson;
import ua.vtc.rozklad.core.timetable.TemplateLessonRepository;
import ua.vtc.rozklad.core.timetable.TimetableVersion;
import ua.vtc.rozklad.core.timetable.TimetableVersionRepository;
import ua.vtc.rozklad.core.timetable.VersionSource;

/**
 * The live-stand case behind the owner's rule of 15.11.2026: 4КІ2 ДК, 3 h a week (Monday every week, Wednesday
 * numerator), НС 30.11–11.12. The old plan 3 × 18 = 54 was never reachable («Додати 4 пари»); now the НС weeks are
 * not planned, so the plan equals the timetable's pairs on theory days and the row is on track. A set plan stays as
 * it was set. The theory endpoint says why, with the academic calendar's 20 weeks as a cross-check.
 */
@CoreIntegrationTest
class AccountingTheoryIT {

    private static final String NS = "a1000000-0000-4000-8000-000000000009";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TimetableVersionRepository versions;

    @Autowired
    private TemplateLessonRepository templateLessons;

    @Autowired
    private AccountingTheory theory;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TestClock clock;

    private MockMvc mvc;
    private UUID semester;
    private UUID ki2;
    private UUID dk;
    private UUID pe;
    private UUID teacher;

    @BeforeEach
    void data() throws Exception {
        mvc = ApiTestSupport.mockMvc(context);
        clock.stopAt(Instant.parse("2026-11-20T08:00:00Z"));
        semester = id(create("/api/admin/semesters",
                "{\"number\":1,\"startsOn\":\"2026-09-01\",\"endsOn\":\"2026-12-30\"}").andReturn());
        create("/api/admin/specialties", "{\"letters\":\"КІ\",\"name\":\"Комп’ютерна інженерія\"}");
        ki2 = id(create("/api/admin/groups", "{\"code\":\"4КІ2\"}").andReturn());
        dk = id(create("/api/admin/disciplines", "{\"name\":\"Ділова комунікація\",\"shortName\":\"ДК\"}").andReturn());
        pe = id(create("/api/admin/disciplines", "{\"name\":\"Фізичне виховання\",\"shortName\":\"ФВ\"}").andReturn());
        teacher = id(create("/api/admin/teachers", "{\"lastName\":\"Паламарчук\",\"shortName\":\"Паламарчук\"}")
                .andReturn());
    }

    @AfterEach
    void cleanUp() {
        clock.reset();
        ApiTestSupport.cleanDatabase(jdbc);
    }

    @Test
    void weeksOffTheoryAreNotPlannedAndASetPlanStays() throws Exception {
        create("/api/admin/load", """
                {"semesterId":"%s","groupId":"%s","disciplineId":"%s","hoursPerWeek":3}""".formatted(semester, ki2, dk))
                .andExpect(status().isCreated());
        create("/api/admin/load", """
                {"semesterId":"%s","groupId":"%s","disciplineId":"%s","hoursPerWeek":2,"plannedHours":36}"""
                .formatted(semester, ki2, pe))
                .andExpect(status().isCreated());
        TimetableVersion version = versions.save(new TimetableVersion(semester, "Розклад", VersionSource.IMPORT));
        lesson(version, 1, 1, Parity.BOTH, dk);
        lesson(version, 3, 2, Parity.NUMERATOR, dk);
        lesson(version, 2, 1, Parity.BOTH, pe);
        mvc.perform(post("/api/admin/timetable/versions/{id}/publish", version.getId()).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"effectiveFrom\":\"2026-09-01\"}"))
                .andExpect(status().isOk());
        create("/api/admin/calendar-periods", """
                {"semesterId":"%s","kindId":"%s","title":"Основи національного спротиву","startsOn":"2026-11-30",
                 "endsOn":"2026-12-11","scope":"GROUPS","groupIds":["%s"]}""".formatted(semester, NS, ki2))
                .andExpect(status().isCreated());
        jdbc.update("""
                INSERT INTO group_semester (semester_id, group_id, theory_weeks, exams, source, updated_at)
                VALUES (?, ?, 20, 4, 'IMPORT', now())""", semester, ki2);

        // 15 понеділків + 8 серед чисельника = 23 пари = 46 год; розклад дає рівно стільки
        mvc.perform(get("/api/admin/accounting").param("semesterId", semester.toString()).with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].discipline.shortName").value("ДК"))
                .andExpect(jsonPath("$[0].plan").value(46))
                .andExpect(jsonPath("$[0].forecast").value(46))
                .andExpect(jsonPath("$[0].status").value("ON_TRACK"))
                .andExpect(jsonPath("$[0].suggestion").value(nullValue()))
                .andExpect(jsonPath("$[0].hoursPerWeek").value(3.0))
                .andExpect(jsonPath("$[0].planSource").value("AUTO"))
                // план, заданий числом, система не чіпає: 16 вівторків дають 32 год із 36 — це справжня нестача
                .andExpect(jsonPath("$[1].discipline.shortName").value("ФВ"))
                .andExpect(jsonPath("$[1].plan").value(36))
                .andExpect(jsonPath("$[1].forecast").value(32))
                .andExpect(jsonPath("$[1].status").value("WILL_UNDERRUN"))
                .andExpect(jsonPath("$[1].planSource").value("FIXED"));

        mvc.perform(get("/api/admin/accounting/theory").param("semesterId", semester.toString()).with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].group.code").value("4КІ2"))
                .andExpect(jsonPath("$[0].semesterWeeks").value(18))
                .andExpect(jsonPath("$[0].theoryWeeks").value(16))
                .andExpect(jsonPath("$[0].numeratorWeeks").value(8))
                .andExpect(jsonPath("$[0].denominatorWeeks").value(8))
                .andExpect(jsonPath("$[0].studyDays").value(77))
                .andExpect(jsonPath("$[0].graphTheoryWeeks").value(20))
                .andExpect(jsonPath("$[0].offPeriods", hasSize(1)))
                .andExpect(jsonPath("$[0].offPeriods[0].kind.code").value("НС"))
                .andExpect(jsonPath("$[0].offPeriods[0].kind.effect").value("SUSPEND"))
                .andExpect(jsonPath("$[0].offPeriods[0].startsOn").value("2026-11-30"))
                .andExpect(jsonPath("$[0].offPeriods[0].endsOn").value("2026-12-11"));
        mvc.perform(get("/api/admin/accounting/theory").with(TestJwts.teacher())).andExpect(status().isForbidden());
    }

    /** The theory of every group is counted from one calendar read: three groups cost what one costs. */
    @Test
    void theoryOfManyGroupsCostsNoMoreStatementsThanOfOne() throws Exception {
        load(ki2);
        long oneGroup = statements(() -> theory.groups(semester, null));
        load(id(create("/api/admin/groups", "{\"code\":\"4КІ3\"}").andReturn()));
        load(id(create("/api/admin/groups", "{\"code\":\"3КІ1\"}").andReturn()));

        long threeGroups = statements(() -> assertThat(theory.groups(semester, null)).hasSize(3));

        assertThat(threeGroups).as("statements for three groups vs one (%d)", oneGroup).isEqualTo(oneGroup);
    }

    private void load(UUID group) throws Exception {
        create("/api/admin/load", """
                {"semesterId":"%s","groupId":"%s","disciplineId":"%s","hoursPerWeek":2}""".formatted(semester, group, dk))
                .andExpect(status().isCreated());
    }

    private void lesson(TimetableVersion version, int weekday, int pair, Parity parity, UUID discipline) {
        TemplateLesson lesson = new TemplateLesson(version.getId(), weekday, pair, parity, ki2, 0, discipline);
        lesson.setTeacherId(teacher);
        templateLessons.save(lesson);
    }

    private long statements(Supplier<?> call) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            call.get();
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(enabled);
        }
    }

    private ResultActions create(String path, String json) throws Exception {
        return mvc.perform(post(path).with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
