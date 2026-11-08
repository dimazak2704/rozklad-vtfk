package ua.vtc.rozklad.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.support.TestJwts;

/** Catalog admin and public API against PostgreSQL: CRUD, natural keys, references, import, search. */
@CoreIntegrationTest
class CatalogApiIT {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CatalogQueries catalog;

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
    void structureCrudWithReferencesAndOptimisticLocking() throws Exception {
        UUID department = id(create("/api/admin/departments", "{\"name\":\"Комп’ютерне відділення\"}")
                .andExpect(header().exists("Location")).andReturn());
        UUID specialty = id(create("/api/admin/specialties",
                "{\"letters\":\"ki\",\"name\":\"Комп’ютерна інженерія\",\"code\":\"123\",\"departmentId\":\"%s\"}"
                        .formatted(department))
                .andExpect(jsonPath("$.letters").value("КІ"))
                .andExpect(jsonPath("$.department.label").value("Комп’ютерне відділення"))
                .andReturn());
        UUID group = id(create("/api/admin/groups", "{\"code\":\"4ki1\",\"size\":25}")
                .andExpect(jsonPath("$.code").value("4КІ1"))
                .andExpect(jsonPath("$.course").value(4))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.shift").value(1))
                .andExpect(jsonPath("$.specialtyId").value(specialty.toString()))
                .andReturn());
        create("/api/admin/groups", "{\"code\":\"2КІ3\"}").andExpect(jsonPath("$.shift").value(2));

        mvc.perform(get("/api/admin/groups").param("q", "4кі").param("sort", "code,desc").with(TestJwts.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].code").value("4КІ1"));

        String stale = "{\"code\":\"4КІ1\",\"size\":26,\"version\":5}";
        mvc.perform(put("/api/admin/groups/{id}", group).with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(stale))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:stale-data"));
        mvc.perform(put("/api/admin/groups/{id}", group).with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"4КІ1\",\"size\":26,\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(26))
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(delete("/api/admin/specialties/{id}", specialty).with(TestJwts.admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Спеціальність «КІ» не можна видалити: до неї належать групи."));
        mvc.perform(delete("/api/admin/departments/{id}", department).with(TestJwts.admin()))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/admin/groups/{id}", group).with(TestJwts.admin())).andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/groups/{id}", group).with(TestJwts.admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Не знайдено"));
    }

    @Test
    void naturalKeysAreUniqueAcrossSpellings() throws Exception {
        create("/api/admin/departments", "{\"name\":\"Радіотехнічне відділення\"}");
        create("/api/admin/departments", "{\"name\":\"радіотехнічне ВІДДІЛЕННЯ\"}").andExpect(status().isConflict());

        create("/api/admin/teachers", "{\"lastName\":\"Кренцін\",\"firstName\":\"Микола\",\"middleName\":\"Дмитрович\"}")
                .andExpect(jsonPath("$.shortName").value("Кренцін М.Д."))
                .andExpect(jsonPath("$.displayName").value("Кренцін"))
                .andExpect(jsonPath("$.linked").value(false));
        create("/api/admin/teachers", "{\"lastName\":\"Кренцін\",\"shortName\":\"Кренцін М. Д.\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Викладач «Кренцін М.Д.» уже існує."));

        create("/api/admin/rooms", "{\"name\":\"с.з.\",\"type\":\"GYM\",\"maxParallel\":3}");
        create("/api/admin/rooms", "{\"name\":\"С.З.\",\"type\":\"GYM\"}").andExpect(status().isConflict());

        create("/api/admin/disciplines", "{\"name\":\"Українська мова\",\"shortName\":\"Укр.мова\"}");
        create("/api/admin/disciplines", "{\"name\":\"Українська мова\",\"shortName\":\"Укр. мова.\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void aliasesAndCompetencesOfTeachers() throws Exception {
        UUID garbolinska = id(create("/api/admin/teachers", "{\"lastName\":\"Гарболінська\",\"shortName\":\"Гарболінська О.\"}")
                .andReturn());
        create("/api/admin/teachers", "{\"lastName\":\"Харченко\",\"shortName\":\"Харченко О.\"}");
        UUID stp = id(create("/api/admin/disciplines", "{\"name\":\"Сучасні технології проєктування\",\"shortName\":\"СТП\"}")
                .andReturn());

        create("/api/admin/teachers/" + garbolinska + "/aliases", "{\"alias\":\"Граболінська О.\"}")
                .andExpect(jsonPath("$.alias").value("Граболінська О."));
        create("/api/admin/teachers/" + garbolinska + "/aliases", "{\"alias\":\"Харченко О.\"}")
                .andExpect(status().isConflict());
        create("/api/admin/teachers/" + garbolinska + "/aliases", "{\"alias\":\"граболінська о\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Такий псевдонім уже є."));
        assertThat(catalog.teacherByShortName("Граболінська О.")).map(TeacherInfo::id).contains(garbolinska);

        mvc.perform(put("/api/admin/teachers/{id}/disciplines", garbolinska).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disciplineIds\":[\"%s\",\"%s\"]}".formatted(stp, UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("disciplineIds[1]"));
        mvc.perform(put("/api/admin/teachers/{id}/disciplines", garbolinska).with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"disciplineIds\":[\"%s\"]}".formatted(stp)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].shortName").value("СТП"));
        assertThat(catalog.hasCompetence(garbolinska, stp)).isTrue();
    }

    @Test
    void groupCodeMustMatchAnExistingSpecialty() throws Exception {
        create("/api/admin/groups", "{\"code\":\"4КІ1\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("code"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("Спеціальність «КІ» не знайдено")));
        create("/api/admin/groups", "{\"code\":\"кренцін\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(containsString("4КІ1")));
        create("/api/admin/teachers", "{\"lastName\":\" \",\"email\":\"не-пошта\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andExpect(jsonPath("$.errors[1].field").value("lastName"))
                .andExpect(jsonPath("$.errors[1].message").value("не може бути пустим"));
    }

    @Test
    void catalogImportUpsertsByNaturalKeysAndFeedsTheSearch() throws Exception {
        String request = """
                {
                  "departments": [{"name": "Комп’ютерне відділення", "shortName": "Комп’ютерне"}],
                  "specialties": [{"letters": "КІ", "name": "Комп’ютерна інженерія", "departmentName": "Комп’ютерне відділення"}],
                  "teachers": [
                    {"shortName": "Кренцін М.Д.", "cycleCommissionName": "Циклова комісія комп’ютерної інженерії"},
                    {"shortName": "Гарболінська О."}
                  ],
                  "disciplines": [{"shortName": "СТП", "name": "Сучасні технології проєктування"}, {"shortName": "Вст.спц."}],
                  "rooms": [{"name": "6", "type": "COMPUTER_LAB"}, {"name": "с.з.", "type": "GYM", "maxParallel": 3}],
                  "groups": [{"code": "4ki1", "homeRoomName": "6", "curatorShortName": "Кренцін М. Д.", "subgroups": [1, 2]},
                             {"code": "4ВЕ"}],
                  "aliases": [{"type": "TEACHER", "alias": "Граболінська О.", "target": "Гарболінська О."},
                              {"type": "DISCIPLINE", "alias": "Вс.спц.", "target": "Вст.спц."}]
                }""";

        mvc.perform(post("/api/admin/import/catalog").with(TestJwts.service())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(16))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.ids.groups['4ki1']").exists())
                .andExpect(jsonPath("$.warnings", hasItem(containsString("«ВЕ» створено автоматично"))));
        mvc.perform(post("/api/admin/import/catalog").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(0));

        GroupInfo group = catalog.groupByCode("4КІ1").orElseThrow();
        assertThat(group.subgroups()).containsExactly(1, 2);
        assertThat(catalog.teacherByShortName("Граболінська О.")).map(TeacherInfo::shortName).contains("Гарболінська О.");
        assertThat(catalog.disciplineByShortName("Вст.спц")).hasValueSatisfying(discipline ->
                assertThat(discipline.name()).isEqualTo("Вст.спц."));
        mvc.perform(get("/api/admin/disciplines").param("needsReview", "true").with(TestJwts.admin()))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].shortName").value("Вст.спц."));

        mvc.perform(get("/api/public/search").param("q", "4ki1"))
                .andExpect(jsonPath("$[0].label").value("4КІ1"))
                .andExpect(jsonPath("$[0].type").value("GROUP"));
        mvc.perform(get("/api/public/search").param("q", "кренц"))
                .andExpect(jsonPath("$[0].label").value("Кренцін М.Д."));
        mvc.perform(get("/api/public/search").param("q", "6"))
                .andExpect(jsonPath("$[0].label").value("ауд. 6"));
        mvc.perform(get("/api/public/groups"))
                .andExpect(jsonPath("$[0].code").value("4ВЕ"))
                .andExpect(jsonPath("$[1].specialty.name").value("Комп’ютерна інженерія"))
                .andExpect(jsonPath("$[1].department").value("Комп’ютерне відділення"));
    }

    @Test
    void importIsAllOrNothing() throws Exception {
        mvc.perform(post("/api/admin/import/catalog").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teachers": [{"shortName": "Кренцін М.Д."}],
                                 "aliases": [{"type": "TEACHER", "alias": "Кренцин", "target": "Невідомий В."}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("aliases[0].target"));

        assertThat(catalog.teacherByShortName("Кренцін М.Д.")).isEmpty();
    }

    @Test
    void importNeedsAdminOrService() throws Exception {
        mvc.perform(post("/api/admin/import/catalog").with(TestJwts.teacher())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/teachers").with(TestJwts.service())).andExpect(status().isForbidden());
    }

    private ResultActions create(String path, String json) throws Exception {
        return mvc.perform(post(path).with(TestJwts.admin()).contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
