package ua.vtc.rozklad.core.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.vtc.rozklad.core.catalog.ApiTestSupport.id;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import ua.vtc.rozklad.contracts.Parity;
import ua.vtc.rozklad.contracts.UnitWeight;
import ua.vtc.rozklad.core.catalog.ApiTestSupport;
import ua.vtc.rozklad.core.load.internal.LoadDerivationService;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.support.TestJwts;
import ua.vtc.rozklad.core.timetable.Lesson;
import ua.vtc.rozklad.core.timetable.LessonOrigin;
import ua.vtc.rozklad.core.timetable.LessonRepository;
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

    @Autowired
    private LessonRepository datedLessons;

    @Autowired
    private LoadDerivationService derivation;

    @Autowired
    private LoadItemRepository loadItems;

    @Autowired
    private Clock clock;

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

    /**
     * The Excel import stores no load teachers per subgroup: the timetable alone keeps a subgroup in use,
     * by its template lessons (any version) or its dated lessons from today on.
     */
    @Test
    void subgroupTaughtInTheTimetableCannotBeDeletedOrRenumbered() throws Exception {
        String subgroups = "/api/admin/groups/{id}/subgroups";
        UUID subgroup1 = UUID.fromString(ApiTestSupport.read(mvc.perform(get(subgroups, group)
                .with(TestJwts.admin())).andReturn(), "$[0].id"));
        UUID subgroup2 = UUID.fromString(ApiTestSupport.read(mvc.perform(get(subgroups, group)
                .with(TestJwts.admin())).andReturn(), "$[1].id"));
        TimetableVersion version = versions.save(new TimetableVersion(semester, "Імпорт Excel", VersionSource.IMPORT));
        lesson(version, 3, 3, Parity.BOTH, 2, english, mozharovska);
        Lesson dated = new Lesson(semester, LocalDate.now(clock).plusDays(1), 2, group, 1, english, LessonOrigin.TEMPLATE);
        datedLessons.save(dated);

        mvc.perform(delete(subgroups + "/{sub}", group, subgroup2).with(TestJwts.admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("розклад (версії), занять: 1")));
        mvc.perform(put(subgroups + "/{sub}", group, subgroup2).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"number\":3,\"version\":0}"))
                .andExpect(status().isConflict());
        mvc.perform(delete(subgroups + "/{sub}", group, subgroup1).with(TestJwts.admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("від сьогодні, занять: 1")));

        datedLessons.delete(dated);
        mvc.perform(delete(subgroups + "/{sub}", group, subgroup1).with(TestJwts.admin()))
                .andExpect(status().isNoContent());
    }

    /**
     * A double-clicked «derive from version» (or derive racing the importer): both calls succeed, each
     * load item exists once, so the plan hours are not doubled.
     */
    @Test
    void concurrentDerivationsCreateEachItemOnce() throws Exception {
        TimetableVersion version = versions.save(new TimetableVersion(semester, "Імпорт Excel", VersionSource.IMPORT));
        lesson(version, 1, 1, Parity.BOTH, 0, stp, krentsin);
        lesson(version, 3, 3, Parity.BOTH, 1, english, krysak);
        lesson(version, 3, 3, Parity.BOTH, 2, english, mozharovska);
        lesson(version, 4, 1, Parity.DENOMINATOR, 0, math, null);
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return derivation.derive(version.getId());
                }));
            }
            for (Future<?> run : runs) {
                run.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT 1 FROM load_item WHERE semester_id = ?
                                      GROUP BY group_id, discipline_id, lesson_kind, split_subgroups
                                      HAVING count(*) > 1) duplicates
                """, Integer.class, semester)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM load_item WHERE semester_id = ?", Integer.class,
                semester)).isEqualTo(3);
    }

    /** The natural key is a unique index: a writer that skips the service check still cannot add a copy. */
    @Test
    void databaseRefusesASecondItemWithTheSameKey() {
        loadItems.saveAndFlush(new LoadItem(semester, group, stp, BigDecimal.valueOf(2), 36));

        assertThatThrownBy(() -> loadItems.saveAndFlush(new LoadItem(semester, group, stp, BigDecimal.valueOf(4), 72)))
                .isInstanceOf(DataIntegrityViolationException.class);
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

    /**
     * r4-ai-imports-48: a curriculum or program row does not say whether the group is split; it updates the
     * split item of the timetable (teachers per subgroup kept) instead of creating a second whole-group one.
     */
    @Test
    void importWithoutSplitUpdatesTheSplitItemThatIsThere() throws Exception {
        UUID item = id(create("/api/admin/load", """
                {"semesterId":"%s","groupId":"%s","disciplineId":"%s","hoursPerWeek":2,"splitSubgroups":true,
                 "teachers":[{"subgroup":1,"teacherId":"%s"},{"subgroup":2,"teacherId":"%s"}]}"""
                .formatted(semester, group, english, krysak, mozharovska)).andReturn());
        String curriculumRow = """
                {"semesterId":"%s","items":[{"groupCode":"4КІ1","disciplineShortName":"Ін.мова",
                 "lessonKind":"MIXED","hoursPerWeek":2,"plannedHours":44,"controlForm":"CREDIT",
                 "source":"CURRICULUM"}]}""".formatted(semester);

        mvc.perform(post("/api/admin/import/load").with(TestJwts.service()).contentType(MediaType.APPLICATION_JSON)
                        .content(curriculumRow))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.ids['4КІ1 | Ін.мова | MIXED | split']").value(item.toString()));

        mvc.perform(get("/api/admin/load").param("semesterId", semester.toString()).with(TestJwts.admin()))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(item.toString()))
                .andExpect(jsonPath("$[0].splitSubgroups").value(true))
                .andExpect(jsonPath("$[0].plannedHours").value(44))
                .andExpect(jsonPath("$[0].controlForm").value("CREDIT"))
                .andExpect(jsonPath("$[0].source").value("CURRICULUM"))
                .andExpect(jsonPath("$[0].teachers", hasSize(2)));

        // a repeated import changes nothing
        mvc.perform(post("/api/admin/import/load").with(TestJwts.service()).contentType(MediaType.APPLICATION_JSON)
                        .content(curriculumRow))
                .andExpect(jsonPath("$.unchanged").value(1));

        // an explicit split = false still means the whole-group item, which is created next to the split one
        mvc.perform(post("/api/admin/import/load").with(TestJwts.service()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"semesterId":"%s","items":[{"groupCode":"4КІ1","disciplineShortName":"Ін.мова",
                                 "hoursPerWeek":1,"splitSubgroups":false,"source":"MANUAL"}]}""".formatted(semester)))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.ids['4КІ1 | Ін.мова | MIXED | whole']").exists());

        // with both a whole-group and a split item a row without the split is ambiguous: refused, nothing written
        mvc.perform(post("/api/admin/import/load").with(TestJwts.service()).contentType(MediaType.APPLICATION_JSON)
                        .content(curriculumRow.replace("\"plannedHours\":44", "\"plannedHours\":40")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("items[0].splitSubgroups"));
        mvc.perform(get("/api/admin/load/" + item).with(TestJwts.admin()))
                .andExpect(jsonPath("$.plannedHours").value(44));
    }

    /** Without any item of the group × discipline a row without the split creates a whole-group item. */
    @Test
    void importWithoutSplitCreatesAWholeGroupItem() throws Exception {
        mvc.perform(post("/api/admin/import/load").with(TestJwts.service()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"semesterId":"%s","items":[{"groupCode":"4КІ1","disciplineShortName":"Матем.",
                                 "hoursPerWeek":2,"source":"PROGRAM"}]}""".formatted(semester)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.ids['4КІ1 | Матем. | MIXED | whole']").exists());
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
