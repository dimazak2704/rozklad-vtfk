package ua.vtc.rozklad.core.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.vtc.rozklad.core.catalog.ApiTestSupport.id;
import static ua.vtc.rozklad.core.catalog.ApiTestSupport.read;

import java.time.LocalDate;
import java.util.List;
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

/** «Види періодів» and periods that take a kind, a room and keep the legacy kind working (CONTRACTS §2). */
@CoreIntegrationTest
@RecordApplicationEvents
class PeriodKindApiIT {

    private static final String SEMESTER_I = """
            {"academicYear":"2026/2027","number":1,"startsOn":"2026-09-01","endsOn":"2026-12-30"}""";
    private static final String KINDS = "/api/admin/period-kinds";
    private static final String PERIODS = "/api/admin/calendar-periods";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CalendarQueries calendar;

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
    }

    @Test
    void builtinKindsAreSeededWithTheCollegeCodes() throws Exception {
        mvc.perform(get(KINDS).with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(15)))
                .andExpect(jsonPath("$[0].code").value("К"))
                .andExpect(jsonPath("$[0].effect").value("DAY_OFF"))
                .andExpect(jsonPath("$[0].color").value("#FFFFCC"))
                .andExpect(jsonPath("$[3].code").value("НП"))
                .andExpect(jsonPath("$[3].name").value("Практика"))
                .andExpect(jsonPath("$[7].code").value("ЗУ"))
                .andExpect(jsonPath("$[7].aliases[0]").value("З"))
                .andExpect(jsonPath("$[8].code").value("НС"))
                .andExpect(jsonPath("$[8].effect").value("SUSPEND"))
                .andExpect(jsonPath("$[8].noteTemplate").value("{хто} – {назва} з {від}р. по {до} р.[, ауд. {ауд}]"))
                .andExpect(jsonPath("$[8].builtin").value(true))
                .andExpect(jsonPath("$[8].usage").value(0))
                .andExpect(jsonPath("$[9].code").value("І"))
                .andExpect(jsonPath("$[9].effect").value("SUSPEND"))
                .andExpect(jsonPath("$[12].code").value("ФН"))
                .andExpect(jsonPath("$[12].effect").value("ONLINE"))
                .andExpect(jsonPath("$[13].effect").value("INFO"))
                .andExpect(jsonPath("$[14].code").value("ВКР"));
        assertThat(jdbc.queryForObject("SELECT id FROM period_kind WHERE code = 'І'", UUID.class))
                .isEqualTo(CalendarPeriodKind.EXAM_SESSION.builtinKindId());
    }

    @Test
    void kindsAreValidatedAndCodesDoNotClash() throws Exception {
        kind(post(KINDS), """
                {"code":"Х Х","name":"Пробіл","effect":"SUSPEND"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("code"))
                .andExpect(jsonPath("$.errors[0].message").value("Код — від 1 до 10 літер чи цифр, без пробілів."));
        kind(post(KINDS), """
                {"code":"ХХ","name":"Блок","effect":"BLOCK"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("effect"));
        kind(post(KINDS), """
                {"code":"ХХ","name":"Колір","effect":"INFO","color":"red"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("color"));
        kind(post(KINDS), """
                {"code":"нс","name":"Дубль","effect":"SUSPEND"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Код «нс» уже має вид «Національний спротив»."));
        kind(post(KINDS), """
                {"code":"ХХ","name":"Псевдонім","effect":"SUSPEND","aliases":["з"]}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Код «з» уже має вид «Збори»."));

        UUID created = id(kind(post(KINDS), """
                {"code":"ХХ","name":"Новий вид","effect":"SUSPEND","color":"#ff6600","aliases":["X1","x1","ХХ"]}""")
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.builtin").value(false))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.color").value("#FF6600"))
                .andExpect(jsonPath("$.aliases", hasSize(1)))
                .andExpect(jsonPath("$.position").value(160))
                .andReturn());

        UUID ns = builtinId("НС");
        kind(put(KINDS + "/" + ns), """
                {"code":"NS","name":"Національний спротив","effect":"SUSPEND"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Код вбудованого виду не змінюється."));
        kind(put(KINDS + "/" + created), """
                {"code":"ХХ","name":"Новий вид","effect":"SUSPEND","version":7}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:stale-data"));
        kind(put(KINDS + "/" + created), """
                {"code":"ХХ","name":"Перейменований","effect":"SUSPEND","version":0}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Перейменований"))
                .andExpect(jsonPath("$.aliases", hasSize(0)))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(events.stream(ScheduleChanged.class)).as("вид без періодів").isEmpty();

        mvc.perform(get(KINDS).param("active", "false").with(TestJwts.admin()))
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(delete(KINDS + "/" + ns).with(TestJwts.admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Вбудований вид не видаляють: його можна вимкнути."));
        mvc.perform(delete(KINDS + "/" + created).with(TestJwts.admin())).andExpect(status().isNoContent());
        mvc.perform(get(KINDS + "/" + created).with(TestJwts.admin())).andExpect(status().isNotFound());
        mvc.perform(get(KINDS).with(TestJwts.teacher())).andExpect(status().isForbidden());
    }

    /** НС for 4КІ1 with room 401: the legacy {@code kind} is derived, the block names its kind and room. */
    @Test
    void periodTakesAKindAndARoomAndTheScheduleShowsThem() throws Exception {
        UUID semester = id(create("/api/admin/semesters", SEMESTER_I).andReturn());
        create("/api/admin/specialties", "{\"letters\":\"КІ\",\"name\":\"Комп’ютерна інженерія\"}");
        UUID group = id(create("/api/admin/groups", "{\"code\":\"4КІ1\"}").andReturn());
        UUID room = id(create("/api/admin/rooms", "{\"name\":\"401\",\"type\":\"CLASSROOM\"}").andReturn());
        UUID ns = builtinId("НС");
        String onsPeriod = """
                {"semesterId":"%s","kindId":"%s","title":"Основи національного спротиву",
                 "startsOn":"2026-11-16","endsOn":"2026-11-27","scope":"GROUPS","groupIds":["%s"],"roomId":"%s",
                 "orderNote":"4КІ1 – Основи національного спротиву з 16.11.26р. по 27.11.26 р., ауд. 401"}""";

        UUID period = id(create(PERIODS, onsPeriod.formatted(semester, ns, group, room))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("PRACTICE"))
                .andExpect(jsonPath("$.kindId").value(ns.toString()))
                .andExpect(jsonPath("$.periodKind.code").value("НС"))
                .andExpect(jsonPath("$.periodKind.effect").value("SUSPEND"))
                .andExpect(jsonPath("$.roomId").value(room.toString()))
                .andExpect(jsonPath("$.room.name").value("401"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andReturn());

        LocalDate friday = LocalDate.of(2026, 11, 20);
        assertThat(calendar.isGroupStudyDay(friday, group)).isFalse();
        assertThat(calendar.isStudyDay(friday)).isTrue();
        assertThat(calendar.orderNotes(friday))
                .containsExactly("4КІ1 – Основи національного спротиву з 16.11.26р. по 27.11.26 р., ауд. 401");
        assertThat(calendar.periodsOn(friday, group)).singleElement().satisfies(info -> {
            assertThat(info.periodKind().code()).isEqualTo("НС");
            assertThat(info.roomId()).isEqualTo(room);
        });
        mvc.perform(get("/api/public/schedule/group/{id}?from=2026-11-20&to=2026-11-20", group).with(TestJwts.teacher()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].studyDay").value(false))
                .andExpect(jsonPath("$.days[0].note").value("Основи національного спротиву"))
                .andExpect(jsonPath("$.days[0].practices[0].kind.code").value("НС"))
                .andExpect(jsonPath("$.days[0].practices[0].kind.name").value("Національний спротив"))
                .andExpect(jsonPath("$.days[0].practices[0].kind.effect").value("SUSPEND"))
                .andExpect(jsonPath("$.days[0].practices[0].room.name").value("401"));

        // кімнату й керівників мають лише види «група не на парах»
        create(PERIODS, """
                {"semesterId":"%s","kind":"HOLIDAY","title":"Свято","startsOn":"2026-10-14","endsOn":"2026-10-14",
                 "roomId":"%s"}""".formatted(semester, room))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("roomId"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Аудиторію вказують лише для виду, коли група не на парах."));
        create(PERIODS, onsPeriod.formatted(semester, ns, group, UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Аудиторію не знайдено."));
        create(PERIODS, onsPeriod.formatted(semester, UUID.randomUUID(), group, room))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("kindId"))
                .andExpect(jsonPath("$.errors[0].message").value("Вид періоду не знайдено."));
        create(PERIODS, """
                {"semesterId":"%s","title":"Без виду","startsOn":"2026-10-14","endsOn":"2026-10-14"}"""
                .formatted(semester))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Вкажіть вид періоду."));

        // змінений вручну рядок імпорту стає ручним
        jdbc.update("UPDATE calendar_period SET source = 'IMPORT', source_key = 'k1' WHERE id = ?", period);
        mvc.perform(put(PERIODS + "/" + period).with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(onsPeriod.formatted(semester, ns, group, room).replace("}", ",\"version\":0}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.sourceKey").value("k1"));
    }

    @Test
    void legacyKindStillWorksAndAnInactiveKindCannotBeChosenAnew() throws Exception {
        UUID semester = id(create("/api/admin/semesters", SEMESTER_I).andReturn());
        UUID holiday = id(create(PERIODS, """
                {"semesterId":"%s","kind":"HOLIDAY","title":"Зимові канікули","startsOn":"2026-12-28",
                 "endsOn":"2026-12-30","scope":"ALL"}""".formatted(semester))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("HOLIDAY"))
                .andExpect(jsonPath("$.periodKind.code").value("К"))
                .andExpect(jsonPath("$.kindId").value(CalendarPeriodKind.HOLIDAY.builtinKindId().toString()))
                .andReturn());
        assertThat(calendar.isStudyDay(LocalDate.of(2026, 12, 28))).isFalse();
        create(PERIODS, """
                {"semesterId":"%s","kind":"EXAM_SESSION","title":"Сесія 4 курсу","startsOn":"2026-12-21",
                 "endsOn":"2026-12-25","scope":"COURSES","courses":[4]}""".formatted(semester))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("EXAM_SESSION"))
                .andExpect(jsonPath("$.periodKind.effect").value("SUSPEND"));

        UUID online = id(kind(post(KINDS), """
                {"code":"ДН","name":"Дистанційне навчання","effect":"ONLINE"}""").andReturn());
        create(PERIODS, """
                {"semesterId":"%s","kindId":"%s","title":"Без режиму","startsOn":"2026-10-14","endsOn":"2026-10-14"}"""
                .formatted(semester, online))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("studyMode"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Для виду з ефектом «онлайн» вкажіть режим: офлайн, онлайн або змішаний."));
        create(PERIODS, """
                {"semesterId":"%s","kindId":"%s","studyMode":"ONLINE","title":"Усі дистанційно",
                 "startsOn":"2026-10-14","endsOn":"2026-10-14"}""".formatted(semester, online))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("STUDY_MODE"));

        UUID k = CalendarPeriodKind.HOLIDAY.builtinKindId();
        kind(put(KINDS + "/" + k), """
                {"code":"К","name":"Канікули","effect":"DAY_OFF","color":"#FFFFCC","active":false,
                 "noteTemplate":"{хто}: {назва_мала} з {від}р. по {до} р."}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.usage").value(1));
        assertThat(calendar.isStudyDay(LocalDate.of(2026, 12, 28))).as("вимкнений вид працює далі").isFalse();
        create(PERIODS, """
                {"semesterId":"%s","kind":"HOLIDAY","title":"Ще канікули","startsOn":"2026-12-24","endsOn":"2026-12-24"}"""
                .formatted(semester))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(
                        "Вид «Канікули» вимкнено: увімкніть його в «Видах періодів» або виберіть інший."));
        mvc.perform(put(PERIODS + "/" + holiday).with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"semesterId":"%s","kind":"HOLIDAY","title":"Зимові канікули (уточнено)",
                                 "startsOn":"2026-12-28","endsOn":"2026-12-30","version":0}""".formatted(semester)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Зимові канікули (уточнено)"));

        mvc.perform(delete(KINDS + "/" + online).with(TestJwts.admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("Вид «ДН» має 1 період календаря: спершу видаліть їх або змініть їм вид."));
    }

    /** Effect changes of a kind with periods: ONLINE never switches, a room or supervisors block other effects. */
    @Test
    void effectChangeIsBlockedWhenPeriodsWouldBreak() throws Exception {
        UUID semester = id(create("/api/admin/semesters", SEMESTER_I).andReturn());
        create("/api/admin/specialties", "{\"letters\":\"КІ\",\"name\":\"Комп’ютерна інженерія\"}");
        UUID group = id(create("/api/admin/groups", "{\"code\":\"4КІ1\"}").andReturn());
        UUID room = id(create("/api/admin/rooms", "{\"name\":\"401\",\"type\":\"CLASSROOM\"}").andReturn());
        UUID custom = id(kind(post(KINDS), """
                {"code":"ХХ","name":"Новий вид","effect":"SUSPEND"}""").andReturn());
        create(PERIODS, """
                {"semesterId":"%s","kindId":"%s","title":"Блок","startsOn":"2026-11-16","endsOn":"2026-11-20",
                 "scope":"GROUPS","groupIds":["%s"],"roomId":"%s"}""".formatted(semester, custom, group, room))
                .andExpect(status().isCreated());

        kind(put(KINDS + "/" + custom).param("dryRun", "true"), """
                {"code":"ХХ","name":"Новий вид","effect":"ONLINE","version":0}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectChanged").value(true))
                .andExpect(jsonPath("$.periods").value(1))
                .andExpect(jsonPath("$.dates", hasSize(5)))
                .andExpect(jsonPath("$.groups[0].code").value("4КІ1"))
                .andExpect(jsonPath("$.blockers", hasSize(2)))
                .andExpect(jsonPath("$.canApply").value(false));
        kind(put(KINDS + "/" + custom), """
                {"code":"ХХ","name":"Новий вид","effect":"ONLINE","version":0}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ефект «онлайн» можна ввімкнути чи вимкнути лише для виду "
                        + "без періодів: спершу видаліть періоди цього виду."));
        kind(put(KINDS + "/" + custom), """
                {"code":"ХХ","name":"Новий вид","effect":"INFO","version":0}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("Періоди цього виду мають аудиторію або керівників: спершу приберіть їх."));
        mvc.perform(get(KINDS + "/" + custom).with(TestJwts.admin()))
                .andExpect(jsonPath("$.effect").value("SUSPEND"))
                .andExpect(jsonPath("$.version").value(0));
    }

    /**
     * The builtin ФН has no periods on a fresh install, so its effect may change; its periods still get the legacy
     * {@code STUDY_MODE} column, now without a study mode. V1_204 dropped the old check on that column, so both the
     * admin API and the import create such periods (they used to fail with a misleading 409 «запис уже існує»).
     */
    @Test
    void builtinOnlineKindWithoutPeriodsMayChangeEffectAndStillTakePeriods() throws Exception {
        UUID semester = id(create("/api/admin/semesters", SEMESTER_I).andReturn());
        UUID fn = CalendarPeriodKind.STUDY_MODE.builtinKindId();
        kind(put(KINDS + "/" + fn), """
                {"code":"ФН","name":"Форма навчання","effect":"INFO","noteTemplate":"{хто} з {від}р. по {до} {режим}."}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effect").value("INFO"));

        create(PERIODS, """
                {"semesterId":"%s","kindId":"%s","title":"Позначка","startsOn":"2026-10-14","endsOn":"2026-10-14"}"""
                .formatted(semester, fn))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.periodKind.code").value("ФН"))
                .andExpect(jsonPath("$.periodKind.effect").value("INFO"))
                .andExpect(jsonPath("$.studyMode").value(nullValue()));
        mvc.perform(post("/api/admin/import/calendar-periods").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"academicYear":"2026/2027","periods":[{"sourceKey":"fn","kindCode":"ФН",
                                 "title":"Позначка з графіка","startsOn":"2026-10-19","endsOn":"2026-10-23"}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));
        assertThat(jdbc.queryForList("SELECT kind FROM calendar_period WHERE kind_id = ? AND study_mode IS NULL",
                String.class, fn)).containsExactly("STUDY_MODE", "STUDY_MODE");
    }

    @Test
    void groupSemestersNeedASemesterAndSnapshotListsEveryKind() throws Exception {
        mvc.perform(get("/api/admin/group-semesters").with(TestJwts.admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("semesterId"));
        UUID semester = id(create("/api/admin/semesters", SEMESTER_I).andReturn());
        mvc.perform(get("/api/admin/group-semesters").param("semesterId", semester.toString()).with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        kind(put(KINDS + "/" + builtinId("ВКР")), """
                {"code":"ВКР","name":"Виконання кваліфікаційної роботи","effect":"INFO","active":false,
                 "noteTemplate":"{хто} – {назва} з {від}р. по {до} р."}""")
                .andExpect(status().isOk());
        List<String> codes = read(mvc.perform(get("/api/internal/catalog/snapshot").with(TestJwts.importerService()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodKinds", hasSize(15)))
                .andExpect(jsonPath("$.periodKinds[?(@.code == 'ВКР')].active").value(false))
                .andExpect(jsonPath("$.periodKinds[?(@.code == 'ЗУ')].aliases[0]").value("З"))
                .andReturn(), "$.periodKinds[*].code");
        assertThat(codes).contains("НС", "ФН", "Р");
    }

    private UUID builtinId(String code) {
        return jdbc.queryForObject("SELECT id FROM period_kind WHERE code = ?", UUID.class, code);
    }

    private ResultActions kind(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               String json) throws Exception {
        return mvc.perform(request.with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions create(String path, String json) throws Exception {
        return mvc.perform(post(path).with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
