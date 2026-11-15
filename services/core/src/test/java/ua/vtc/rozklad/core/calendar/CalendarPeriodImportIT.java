package ua.vtc.rozklad.core.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.vtc.rozklad.core.catalog.ApiTestSupport.id;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import ua.vtc.rozklad.contracts.events.ScheduleChanged;
import ua.vtc.rozklad.core.catalog.ApiTestSupport;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.support.TestJwts;

/**
 * {@code POST /api/admin/import/calendar-periods}: the academic calendar split over the semesters of its year,
 * upserted by key, idempotent, leaving the dispatcher's rows alone; a dry run writes nothing.
 */
@CoreIntegrationTest
@RecordApplicationEvents
class CalendarPeriodImportIT {

    private static final String IMPORT = "/api/admin/import/calendar-periods";
    private static final String ONS = "2026/2027|НС|2026-11-16|2026-11-27|3f9a1c2e";
    private static final String WINTER = "2026/2027|К|2026-12-28|2027-01-15|aaaaaaaa";
    private static final String ONS_4EA1 = "2026/2027|НС|2027-01-04|2027-01-15|9b0d4e11";
    private static final String ATTESTATION = "2026/2027|А|2026-12-21|2027-01-29|cccccccc";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEvents events;

    private MockMvc mvc;
    private UUID semesterI;
    private UUID group4ki1;

    @BeforeEach
    void setUp() throws Exception {
        mvc = ApiTestSupport.mockMvc(context);
        semesterI = id(create("/api/admin/semesters", """
                {"academicYear":"2026/2027","number":1,"startsOn":"2026-09-01","endsOn":"2026-12-30"}""").andReturn());
        create("/api/admin/semesters", """
                {"academicYear":"2026/2027","number":2,"startsOn":"2027-01-19","endsOn":"2027-06-30"}""");
        create("/api/admin/specialties", "{\"letters\":\"КІ\",\"name\":\"Комп’ютерна інженерія\"}");
        create("/api/admin/specialties", "{\"letters\":\"ЕА\",\"name\":\"Електроенергетика\"}");
        group4ki1 = id(create("/api/admin/groups", "{\"code\":\"4КІ1\"}").andReturn());
        create("/api/admin/groups", "{\"code\":\"2КІ3\"}");
        create("/api/admin/groups", "{\"code\":\"4ЕА1\"}");
        create("/api/admin/rooms", "{\"name\":\"401\",\"type\":\"CLASSROOM\"}");
    }

    @AfterEach
    void cleanUp() {
        ApiTestSupport.cleanDatabase(jdbc);
    }

    @Test
    void dryRunWritesNothingAndTheImportIsIdempotent() throws Exception {
        int outbox = outbox();

        importCalendar(true, request(ons("Основи національного спротиву")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.canApply").value(true))
                .andExpect(jsonPath("$.created").value(4))
                .andExpect(jsonPath("$.periods", hasSize(4)))
                .andExpect(jsonPath("$.periods[0].sourceKey").value(ONS))
                .andExpect(jsonPath("$.periods[0].storedKey").value(ONS))
                .andExpect(jsonPath("$.periods[0].action").value("CREATE"))
                .andExpect(jsonPath("$.periods[0].periodId").value(nullValue()))
                .andExpect(jsonPath("$.periods[0].semesterId").value(semesterI.toString()))
                .andExpect(jsonPath("$.periods[0].groupCodes[0]").value("4КІ1"))
                .andExpect(jsonPath("$.periods[1].startsOn").value("2026-12-28"))
                .andExpect(jsonPath("$.periods[1].endsOn").value("2026-12-30"))
                .andExpect(jsonPath("$.periods[2].storedKey").value(ATTESTATION + "|S1"))
                .andExpect(jsonPath("$.periods[3].storedKey").value(ATTESTATION + "|S2"))
                .andExpect(jsonPath("$.periods[3].startsOn").value("2027-01-19"))
                .andExpect(jsonPath("$.outOfSemester", hasSize(3)))
                .andExpect(jsonPath("$.outOfSemester[0].sourceKey").value(WINTER))
                .andExpect(jsonPath("$.outOfSemester[0].endsOn").value("2027-01-15"))
                .andExpect(jsonPath("$.outOfSemester[0].kept[0].endsOn").value("2026-12-30"))
                .andExpect(jsonPath("$.outOfSemester[1].sourceKey").value(ONS_4EA1))
                .andExpect(jsonPath("$.outOfSemester[1].kept", hasSize(0)))
                .andExpect(jsonPath("$.outOfSemester[2].kept", hasSize(2)))
                .andExpect(jsonPath("$.groupSemesters.created").value(2))
                .andExpect(jsonPath("$.groupSemesters.skipped", hasSize(0)))
                .andExpect(jsonPath("$.conflicts", hasSize(0)));
        assertThat(count("calendar_period")).isZero();
        assertThat(count("group_semester")).isZero();
        assertThat(outbox()).as("dry run: nothing leaves").isEqualTo(outbox);

        importCalendar(false, request(ons("Основи національного спротиву")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.created").value(4))
                .andExpect(jsonPath("$.periods[0].periodId").isString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_period WHERE source = 'IMPORT'", Integer.class))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("""
                SELECT k.code || ' ' || r.name FROM calendar_period p JOIN period_kind k ON k.id = p.kind_id
                JOIN room r ON r.id = p.room_id WHERE p.source_key = ?""", String.class, ONS)).isEqualTo("НС 401");
        assertThat(jdbc.queryForObject("SELECT theory_weeks FROM group_semester WHERE group_id = ?", Integer.class,
                group4ki1)).isEqualTo(20);
        assertThat(outbox()).isGreaterThan(outbox);
        long announced = events.stream(ScheduleChanged.class).count();

        importCalendar(false, request(ons("Основи національного спротиву")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.deleted").value(0))
                .andExpect(jsonPath("$.unchanged").value(4))
                .andExpect(jsonPath("$.periods[0].action").value("UNCHANGED"))
                .andExpect(jsonPath("$.groupSemesters.unchanged").value(2))
                .andExpect(jsonPath("$.lessons.removed").value(0));
        assertThat(events.stream(ScheduleChanged.class).count()).as("the same file changes nothing")
                .isEqualTo(announced);

        mvc.perform(get("/api/admin/group-semesters").param("semesterId", semesterI.toString()).with(TestJwts.admin()))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].group.code").value("2КІ3"))
                .andExpect(jsonPath("$[0].theoryWeeks").value(nullValue()))
                .andExpect(jsonPath("$[1].group.code").value("4КІ1"))
                .andExpect(jsonPath("$[1].theoryWeeks").value(20))
                .andExpect(jsonPath("$[1].exams").value(4))
                .andExpect(jsonPath("$[1].source").value("IMPORT"));
    }

    /** The import changes its own rows, deletes those the file dropped and leaves the dispatcher's alone. */
    @Test
    void importUpdatesAndPrunesItsRowsButNotTheDispatchers() throws Exception {
        create("/api/admin/calendar-periods", """
                {"semesterId":"%s","kind":"PRACTICE","title":"Практика вручну","startsOn":"2026-11-23",
                 "endsOn":"2026-11-27","scope":"GROUPS","groupIds":["%s"]}""".formatted(semesterI, group4ki1));
        importCalendar(false, request(ons("Основи національного спротиву"))).andExpect(status().isOk());
        UUID ons = jdbc.queryForObject("SELECT id FROM calendar_period WHERE source_key = ?", UUID.class, ONS);
        mvc.perform(put("/api/admin/calendar-periods/{id}", ons).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"semesterId":"%s","kind":"PRACTICE","title":"ОНС (змінено)",
                                 "startsOn":"2026-11-16","endsOn":"2026-11-27","scope":"GROUPS","groupIds":["%s"],
                                 "version":0}""".formatted(semesterI, group4ki1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("MANUAL"));

        String smaller = """
                {"academicYear":"2026/2027","periods":[%s,
                  {"sourceKey":"%s","kindCode":"к","title":"Зимові канікули (уточнено)","startsOn":"2026-12-28",
                   "endsOn":"2027-01-15","scope":"ALL"}]}""".formatted(ons("Основи національного спротиву"), WINTER);
        importCalendar(false, smaller)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skippedManual").value(1))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.deleted").value(2))
                .andExpect(jsonPath("$.periods[0].action").value("SKIP_MANUAL"))
                .andExpect(jsonPath("$.manual[0].periodId").value(ons.toString()))
                .andExpect(jsonPath("$.manual[0].differs", hasSize(4)))
                .andExpect(jsonPath("$.manual[0].differs[0]").value("kindCode"))
                .andExpect(jsonPath("$.manual[0].differs[1]").value("orderNote"))
                .andExpect(jsonPath("$.manual[0].differs[2]").value("roomName"))
                .andExpect(jsonPath("$.manual[0].differs[3]").value("title"))
                .andExpect(jsonPath("$.periods[1].action").value("UPDATE"))
                .andExpect(jsonPath("$.periods[2].action").value("DELETE"))
                .andExpect(jsonPath("$.periods[2].kindCode").value("А"))
                .andExpect(jsonPath("$.periods[3].action").value("DELETE"))
                .andExpect(jsonPath("$.manualOverlaps", hasSize(1)))
                .andExpect(jsonPath("$.manualOverlaps[0].title").value("Практика вручну"))
                .andExpect(jsonPath("$.manualOverlaps[0].sourceKey").value(ONS));
        assertThat(jdbc.queryForObject("SELECT title FROM calendar_period WHERE id = ?", String.class, ons))
                .isEqualTo("ОНС (змінено)");
        assertThat(jdbc.queryForObject("SELECT title FROM calendar_period WHERE source_key = ?", String.class, WINTER))
                .isEqualTo("Зимові канікули (уточнено)");
        assertThat(count("calendar_period")).as("manual, ОНС kept by hand, winter").isEqualTo(3);
    }

    /**
     * An imported row the dispatcher edited keeps its old key; once the file changes that run (new title, so a new
     * key) the import creates the new period and reports the stale manual row as an overlap instead of hiding it.
     */
    @Test
    void editedImportRowWhoseRunChangedInTheFileIsReportedAsAnOverlap() throws Exception {
        importCalendar(false, request(ons("Основи національного спротиву"))).andExpect(status().isOk());
        UUID ons = jdbc.queryForObject("SELECT id FROM calendar_period WHERE source_key = ?", UUID.class, ONS);
        mvc.perform(put("/api/admin/calendar-periods/{id}", ons).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"semesterId":"%s","kind":"PRACTICE","title":"ОНС (змінено)",
                                 "startsOn":"2026-11-16","endsOn":"2026-11-27","scope":"GROUPS","groupIds":["%s"],
                                 "version":0}""".formatted(semesterI, group4ki1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("MANUAL"));

        String renamed = "2026/2027|НС|2026-11-16|2026-11-27|dddddddd";
        importCalendar(true, request(ons("Основи національного спротиву (нова редакція)")).replace(ONS, renamed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periods[0].sourceKey").value(renamed))
                .andExpect(jsonPath("$.periods[0].action").value("CREATE"))
                .andExpect(jsonPath("$.skippedManual").value(0))
                .andExpect(jsonPath("$.manual", hasSize(0)))
                .andExpect(jsonPath("$.manualOverlaps", hasSize(1)))
                .andExpect(jsonPath("$.manualOverlaps[0].periodId").value(ons.toString()))
                .andExpect(jsonPath("$.manualOverlaps[0].title").value("ОНС (змінено)"))
                .andExpect(jsonPath("$.manualOverlaps[0].sourceKey").value(renamed));
    }

    @Test
    void theWholeRequestIsValidatedFirst() throws Exception {
        importCalendar(false, """
                {"academicYear":"2026/2027","periods":[
                  {"sourceKey":"a","kindCode":"ХХ","title":"Невідомий","startsOn":"2026-11-16","endsOn":"2026-11-20"},
                  {"sourceKey":"a","kindCode":"з","title":"Збори","startsOn":"2026-11-16","endsOn":"2026-11-16",
                   "scope":"GROUPS","groupCodes":["4ki1","9ЯЯ9"]},
                  {"sourceKey":"b","kindCode":"К","title":"Канікули","startsOn":"2026-12-28","endsOn":"2026-12-30",
                   "roomName":"401"},
                  {"sourceKey":"c","kindCode":"ФН","title":"Дистанційно","startsOn":"2026-09-01","endsOn":"2026-10-31"}],
                 "groupSemesters":[{"groupCode":"5ХХ1","semesterNumber":1,"theoryWeeks":20}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(6)))
                .andExpect(jsonPath("$.errors[0].field").value("groupSemesters[0].groupCode"))
                .andExpect(jsonPath("$.errors[0].message").value("Групу «5ХХ1» не знайдено."))
                .andExpect(jsonPath("$.errors[1].field").value("periods[0].kindCode"))
                .andExpect(jsonPath("$.errors[1].message").value("Невідомий вид «ХХ»."))
                .andExpect(jsonPath("$.errors[2].field").value("periods[1].groupCodes[1]"))
                .andExpect(jsonPath("$.errors[3].field").value("periods[1].sourceKey"))
                .andExpect(jsonPath("$.errors[3].message").value("Ключ повторюється."))
                .andExpect(jsonPath("$.errors[4].field").value("periods[2].roomName"))
                .andExpect(jsonPath("$.errors[5].field").value("periods[3].studyMode"));
        importCalendar(false, """
                {"academicYear":"2026-27","periods":[]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("academicYear"));
        jdbc.update("UPDATE period_kind SET active = false WHERE code = 'Р'");
        importCalendar(true, """
                {"academicYear":"2026/2027","periods":[
                  {"sourceKey":"r","kindCode":"Р","title":"Резерв","startsOn":"2026-12-21","endsOn":"2026-12-25"}]}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Вид «Р» вимкнено."));
        assertThat(count("calendar_period")).isZero();
    }

    @Test
    void aYearWithoutSemestersStoresNothingAndTheImporterMayCallIt() throws Exception {
        String nextYear = """
                {"academicYear":"2027/2028","periods":[
                  {"sourceKey":"x","kindCode":"НС","title":"ОНС","startsOn":"2027-11-15","endsOn":"2027-11-26",
                   "scope":"GROUPS","groupCodes":["4КІ1"]}],
                 "groupSemesters":[{"groupCode":"4КІ1","semesterNumber":2,"theoryWeeks":18,"exams":3}]}""";

        mvc.perform(post(IMPORT).with(TestJwts.importerService()).header("Idempotency-Key", "import-job:1:calendar")
                        .contentType(MediaType.APPLICATION_JSON).content(nextYear))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.periods", hasSize(0)))
                .andExpect(jsonPath("$.outOfSemester[0].sourceKey").value("x"))
                .andExpect(jsonPath("$.groupSemesters.skipped[0].reason").value("Семестру II 2027/2028 немає в системі."));
        mvc.perform(post(IMPORT).with(TestJwts.teacher()).contentType(MediaType.APPLICATION_JSON).content(nextYear))
                .andExpect(status().isForbidden());
        assertThat(count("calendar_period")).isZero();
    }

    private static String ons(String title) {
        return """
                {"sourceKey":"%s","kindCode":"НС","title":"%s","startsOn":"2026-11-16","endsOn":"2026-11-27",
                 "scope":"GROUPS","groupCodes":["4КІ1"],"roomName":"401",
                 "orderNote":"4КІ1 – Основи національного спротиву з 16.11.26р. по 27.11.26 р., ауд. 401"}"""
                .formatted(ONS, title);
    }

    private static String request(String ons) {
        return """
                {"academicYear":"2026/2027","periods":[%s,
                  {"sourceKey":"%s","kindCode":"К","title":"Зимові канікули","startsOn":"2026-12-28",
                   "endsOn":"2027-01-15","scope":"ALL"},
                  {"sourceKey":"%s","kindCode":"НС","title":"Основи національного спротиву","startsOn":"2027-01-04",
                   "endsOn":"2027-01-15","scope":"GROUPS","groupCodes":["4ЕА1"],"roomName":"401"},
                  {"sourceKey":"%s","kindCode":"А","title":"Атестація","startsOn":"2026-12-21",
                   "endsOn":"2027-01-29","scope":"GROUPS","groupCodes":["2КІ3"]}],
                 "groupSemesters":[{"groupCode":"4КІ-1","semesterNumber":1,"theoryWeeks":20,"exams":4},
                                   {"groupCode":"2КІ3","semesterNumber":1,"theoryWeeks":null,"exams":null}]}"""
                .formatted(ons, WINTER, ONS_4EA1, ATTESTATION);
    }

    private ResultActions importCalendar(boolean dryRun, String json) throws Exception {
        return mvc.perform(post(IMPORT).param("dryRun", String.valueOf(dryRun)).with(TestJwts.admin())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions create(String path, String json) throws Exception {
        return mvc.perform(post(path).with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int outbox() {
        return jdbc.queryForObject("SELECT count(*) FROM event_publication", Integer.class);
    }
}
