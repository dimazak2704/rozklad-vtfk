package ua.vtc.rozklad.core.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import ua.vtc.rozklad.core.catalog.ApiTestSupport;
import ua.vtc.rozklad.core.substitution.fixture.Parallel;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.support.TestJwts;

/** Settings round-trip through jsonb, including a cleared default bell profile; restores the originals. */
@CoreIntegrationTest
class SettingsApiIT {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private SettingsService settings;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc;
    private SystemSettings original;

    @BeforeEach
    void setUp() {
        mvc = ApiTestSupport.mockMvc(context);
        original = settings.current();
    }

    @AfterEach
    void restore() {
        settings.update(original, "test");
    }

    @Test
    void valuesRoundTripAndTheActorIsRecorded() throws Exception {
        mvc.perform(put("/api/admin/settings").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvalMode":"AUTO","confirmTimeoutMinutes":45,"signatoryPosition":"Директор",
                                 "signatoryName":"Світлана ВАСИЛЮК","collegeName":"ВТФК","hoursPerPair":1,
                                 "showTeacherFullNames":true,"activeBellProfileId":null,"version":%d}"""
                                .formatted(settings.version())))
                .andExpect(status().isOk());

        mvc.perform(get("/api/admin/settings").with(TestJwts.admin()))
                .andExpect(jsonPath("$.approvalMode").value("AUTO"))
                .andExpect(jsonPath("$.confirmTimeoutMinutes").value(45))
                .andExpect(jsonPath("$.hoursPerPair").value(1))
                .andExpect(jsonPath("$.showTeacherFullNames").value(true))
                .andExpect(jsonPath("$.activeBellProfileId").doesNotExist());
        assertThat(settings.activeBellProfileId()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT updated_by FROM setting WHERE key = 'accounting.hoursPerPair'",
                String.class)).isEqualTo("admin");
    }

    /** Two administrators save the form they both loaded: one wins, the other gets 409 stale-data. */
    @Test
    void parallelSavesOfTheSameVersionLetOnlyOneWin() throws Exception {
        long version = settings.version();
        String body = """
                {"approvalMode":"CONFIRM","confirmTimeoutMinutes":120,"signatoryPosition":"Директор",
                 "signatoryName":"%s","collegeName":"ВТФК","hoursPerPair":2,
                 "showTeacherFullNames":false,"activeBellProfileId":null,"version":%d}""";

        List<Object> statuses = Parallel.run(
                () -> mvc.perform(put("/api/admin/settings").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body.formatted("Ім'я A", version)))
                        .andReturn().getResponse().getStatus(),
                () -> mvc.perform(put("/api/admin/settings").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body.formatted("Ім'я B", version)))
                        .andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(settings.version()).isEqualTo(version + 1);
        mvc.perform(put("/api/admin/settings").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body.formatted("Ім'я C", version)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:stale-data"));
    }

    @Test
    void unknownBellProfileIsRejected() throws Exception {
        mvc.perform(put("/api/admin/settings").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvalMode":"CONFIRM","confirmTimeoutMinutes":120,"signatoryPosition":"Директор",
                                 "signatoryName":"Ім'я","collegeName":"ВТФК","hoursPerPair":2,
                                 "showTeacherFullNames":false,"activeBellProfileId":"%s","version":%d}"""
                                .formatted(UUID.randomUUID(), settings.version())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("activeBellProfileId"))
                .andExpect(jsonPath("$.errors[0].message").value("Профіль дзвінків не знайдено."));
        assertThat(settings.current()).isEqualTo(original);
    }
}
