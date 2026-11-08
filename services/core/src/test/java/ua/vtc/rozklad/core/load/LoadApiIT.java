package ua.vtc.rozklad.core.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.vtc.rozklad.core.catalog.ApiTestSupport.id;

import java.util.List;
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
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.core.catalog.ApiTestSupport;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.timetable.TemplateLesson;
import ua.vtc.rozklad.core.timetable.TemplateLessonRepository;
import ua.vtc.rozklad.core.timetable.TimetableVersion;
import ua.vtc.rozklad.core.timetable.TimetableVersionRepository;
import ua.vtc.rozklad.core.timetable.VersionSource;

/** Load API against PostgreSQL: CRUD with weekly pattern, derivation from a version, import, units. */
@CoreIntegrationTest
class LoadApiIT {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LoadQueries loadQueries;

    @Autowired
    private TimetableVersionRepository versions;

    @Autowired
    private TemplateLessonRepository templateLessons;

    private MockMvc mvc;
    private UUID semester;
    private UUID group;
    private UUID stp;
    private UUID english;
    private UUID math;
    private UUID krentsin;
    private UUID krysak;
    private UUID mozharovska;

    @BeforeEach
    void catalog() throws Exception {
        mvc = ApiTestSupport.mockMvc(context);
        semester = id(create("/api/admin/semesters",
                "{\"number\":1,\"startsOn\":\"2026-09-01\",\"endsOn\":\"2026-12-30\"}").andReturn());
        create("/api/admin/specialties", "{\"letters\":\"КІ\",\"name\":\"Комп’ютерна інженерія\"}");
        group = id(create("/api/admin/groups", "{\"code\":\"4КІ1\"}").andReturn());
        create("/api/admin/groups/" + group + "/subgroups", "{\"number\":1}");
        create("/api/admin/groups/" + group + "/subgroups", "{\"number\":2}");
        stp = id(create("/api/admin/disciplines", "{\"name\":\"Сучасні технології проєктування\",\"shortName\":\"СТП\"}")
                .andReturn());
        english = id(create("/api/admin/disciplines", "{\"name\":\"Іноземна мова\",\"shortName\":\"Ін.мова\"}").andReturn());
        math = id(create("/api/admin/disciplines", "{\"name\":\"Математика\",\"shortName\":\"Матем.\"}").andReturn());
        krentsin = id(create("/api/admin/teachers", "{\"lastName\":\"Кренцін\",\"shortName\":\"Кренцін М.Д.\"}").andReturn());
        krysak = id(create("/api/admin/teachers", "{\"lastName\":\"Крисак\",\"shortName\":\"Крисак Л.\"}").andReturn());
        mozharovska = id(create("/api/admin/teachers", "{\"lastName\":\"Можаровська\",\"shortName\":\"Можаровська\"}")
                .andReturn());
    }

    @AfterEach
    void cleanUp() {
        ApiTestSupport.cleanDatabase(jdbc);
    }

    @Test
    void crudWithWeeklyPatternAndDefaultPlannedHours() throws Exception {
        UUID item = id(create("/api/admin/load", """
                {"semesterId":"%s","groupId":"%s","disciplineId":"%s","hoursPerWeek":3,
                 "teachers":[{"subgroup":0,"teacherId":"%s"}]}""".formatted(semester, group, stp, krentsin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pattern.whole").value(1))
                .andExpect(jsonPath("$.pattern.half").value(1))
                .andExpect(jsonPath("$.plannedHours").value(54))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.group.code").value("4КІ1"))
                .andExpect(jsonPath("$.teachers[0].teacher.shortName").value("Кренцін М.Д."))
                .andReturn());

        mvc.perform(put("/api/admin/load/{id}", item).with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"semesterId":"%s","groupId":"%s","disciplineId":"%s","hoursPerWeek":4,"plannedHours":80,
                                 "version":0}""".formatted(semester, group, stp)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pattern.whole").value(2))
                .andExpect(jsonPath("$.pattern.half").value(0))
                .andExpect(jsonPath("$.plannedHours").value(80))
                .andExpect(jsonPath("$.teachers", hasSize(0)));

        create("/api/admin/load", """
                {"semesterId":"%s","groupId":"%s","disciplineId":"%s","hoursPerWeek":2}""".formatted(semester, group, stp))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/admin/load").param("semesterId", semester.toString()).with(TestJwts.admin()))
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/api/admin/load").with(TestJwts.admin())).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/admin/load/{id}", item).with(TestJwts.admin())).andExpect(status().isNoContent());
    }

    @Test
    void splitLoadNeedsExistingSubgroupsAndBlocksTheirDeletion() throws Exception {
        create("/api/admin/load", """
                {"semesterId":"%s","groupId":"%s","disciplineId":"%s","hoursPerWeek":2.55,"splitSubgroups":true,
                 "teachers":[{"subgroup":3,"teacherId":"%s"},{"subgroup":0,"teacherId":"%s"}]}"""
                .formatted(semester, group, english, krysak, mozharovska))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("hoursPerWeek"));
        create("/api/admin/load", """
                {"semesterId":"%s","groupId":"%s","disciplineId":"%s","hoursPerWeek":3,"splitSubgroups":true,
                 "teachers":[{"subgroup":3,"teacherId":"%s"},{"subgroup":0,"teacherId":"%s"}]}"""
                .formatted(semester, group, english, krysak, mozharovska))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("teachers[0].subgroup"))
                .andExpect(jsonPath("$.errors[1].field").value("teachers[1].subgroup"));
        create("/api/admin/load", """
                {"semesterId":"%s","groupId":"%s","disciplineId":"%s","hoursPerWeek":3,"splitSubgroups":true,
                 "teachers":[{"subgroup":1,"teacherId":"%s"},{"subgroup":2,"teacherId":"%s"}]}"""
                .formatted(semester, group, english, krysak, mozharovska))
                .andExpect(status().isCreated());

        List<WeeklyUnit> units = loadQueries.weeklyUnits(semester);
        assertThat(units).hasSize(4);
        assertThat(units).filteredOn(unit -> unit.subgroup() == 2)
                .extracting(WeeklyUnit::weight, WeeklyUnit::teacherId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(UnitWeight.WHOLE, mozharovska),
                        org.assertj.core.groups.Tuple.tuple(UnitWeight.HALF, mozharovska));

        UUID subgroup2 = UUID.fromString(ApiTestSupport.read(mvc.perform(get("/api/admin/groups/{id}/subgroups", group)
                .with(TestJwts.admin())).andReturn(), "$[1].id"));
        mvc.perform(delete("/api/admin/groups/{id}/subgroups/{sub}", group, subgroup2).with(TestJwts.admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("навантаження")));
    }

    @Test
    void deriveFromVersionCountsBothAsOneAndHalvesAsHalf() throws Exception {
        TimetableVersion version = versions.save(new TimetableVersion(semester, "Імпорт Excel", VersionSource.IMPORT));
        lesson(version, 1, 1, Parity.BOTH, 0, stp, krentsin);
        lesson(version, 2, 2, Parity.NUMERATOR, 0, stp, krentsin);
        lesson(version, 3, 3, Parity.BOTH, 1, english, krysak);
        lesson(version, 3, 3, Parity.BOTH, 2, english, mozharovska);
        lesson(version, 4, 1, Parity.DENOMINATOR, 0, math, null);
        create("/api/admin/load", """
                {"semesterId":"%s","groupId":"%s","disciplineId":"%s","hoursPerWeek":4}""".formatted(semester, group, math))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/admin/load/derive-from-version/{id}", version.getId()).with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.skipped[0].discipline.shortName").value("Матем."))
                .andExpect(jsonPath("$.items[?(@.discipline.shortName == 'СТП')].hoursPerWeek").value(3.0))
                .andExpect(jsonPath("$.items[?(@.discipline.shortName == 'СТП')].source").value("TIMETABLE_DERIVED"))
                .andExpect(jsonPath("$.items[?(@.discipline.shortName == 'Ін.мова')].splitSubgroups").value(true))
                .andExpect(jsonPath("$.items[?(@.discipline.shortName == 'Ін.мова')].hoursPerWeek").value(2.0));

        mvc.perform(post("/api/admin/load/derive-from-version/{id}", version.getId()).with(TestJwts.admin()))
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.removed").value(0));
        mvc.perform(post("/api/admin/load/derive-from-version/{id}", UUID.randomUUID()).with(TestJwts.admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void importLoadByNaturalKeys() throws Exception {
        String request = """
                {"semesterId":"%s","items":[
                  {"groupCode":"4ki1","disciplineShortName":"СТП","hoursPerWeek":2,"source":"CURRICULUM",
                   "teachers":[{"subgroup":0,"teacherShortName":"Кренцін М. Д."}]}]}""".formatted(semester);

        mvc.perform(post("/api/admin/import/load").with(TestJwts.service()).contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.ids['4КІ1 | СТП | MIXED | whole']").exists());
        mvc.perform(post("/api/admin/import/load").with(TestJwts.service()).contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.unchanged").value(1));
        assertThat(loadQueries.teachesInSemester(krentsin, stp, semester)).isTrue();

        mvc.perform(post("/api/admin/import/load").with(TestJwts.service()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"semesterId":"%s","items":[{"groupCode":"9ЖЖ9","disciplineShortName":"СТП",
                                 "hoursPerWeek":2,"source":"CURRICULUM"}]}""".formatted(semester)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("items[0].groupCode"));
    }

    /** A program carries hours only: it must not erase the teacher, room requirements or note of the item. */
    @Test
    void importWithoutTeachersKeepsWhatTheRequestDoesNotCarry() throws Exception {
        UUID room = id(create("/api/admin/rooms", "{\"name\":\"6\",\"type\":\"COMPUTER_LAB\",\"capacity\":30}")
                .andReturn());
        UUID item = id(create("/api/admin/load", """
                {"semesterId":"%s","groupId":"%s","disciplineId":"%s","hoursPerWeek":3,"plannedHours":54,
                 "teachers":[{"subgroup":0,"teacherId":"%s"}],"requiredRoomType":"COMPUTER_LAB",
                 "preferredRoomId":"%s","controlForm":"EXAM","note":"з розкладу"}"""
                .formatted(semester, group, stp, krentsin, room)).andReturn());

        mvc.perform(post("/api/admin/import/load").with(TestJwts.service()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"semesterId":"%s","items":[{"groupCode":"4КІ1","disciplineShortName":"СТП",
                                 "lessonKind":"MIXED","hoursPerWeek":3,"plannedHours":60,"source":"PROGRAM"}]}"""
                                .formatted(semester)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1));

        mvc.perform(get("/api/admin/load/" + item).with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plannedHours").value(60))
                .andExpect(jsonPath("$.source").value("PROGRAM"))
                .andExpect(jsonPath("$.teachers[0].teacher.id").value(krentsin.toString()))
                .andExpect(jsonPath("$.requiredRoomType").value("COMPUTER_LAB"))
                .andExpect(jsonPath("$.preferredRoom.id").value(room.toString()))
                .andExpect(jsonPath("$.controlForm").value("EXAM"))
                .andExpect(jsonPath("$.note").value("з розкладу"));

        // явний порожній список — свідоме зняття викладачів
        mvc.perform(post("/api/admin/import/load").with(TestJwts.service()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"semesterId":"%s","items":[{"groupCode":"4КІ1","disciplineShortName":"СТП",
                                 "hoursPerWeek":3,"plannedHours":60,"source":"PROGRAM","teachers":[]}]}"""
                                .formatted(semester)))
                .andExpect(jsonPath("$.updated").value(1));
        mvc.perform(get("/api/admin/load/" + item).with(TestJwts.admin()))
                .andExpect(jsonPath("$.teachers").isEmpty());
    }

    private void lesson(TimetableVersion version, int weekday, int pair, Parity parity, int subgroup, UUID discipline,
                        UUID teacher) {
        TemplateLesson lesson = new TemplateLesson(version.getId(), weekday, pair, parity, group, subgroup, discipline);
        lesson.setTeacherId(teacher);
        templateLessons.save(lesson);
    }

    private ResultActions create(String path, String json) throws Exception {
        return mvc.perform(post(path).with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
