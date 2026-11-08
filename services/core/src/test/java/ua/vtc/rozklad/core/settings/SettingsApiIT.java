package ua.vtc.rozklad.core.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
                                 "showTeacherFullNames":true,"activeBellProfileId":null}"""))
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

    @Test
    void unknownBellProfileIsRejected() throws Exception {
        mvc.perform(put("/api/admin/settings").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approvalMode":"CONFIRM","confirmTimeoutMinutes":120,"signatoryPosition":"Директор",
                                 "signatoryName":"Ім'я","collegeName":"ВТФК","hoursPerPair":2,
                                 "showTeacherFullNames":false,"activeBellProfileId":"%s"}""".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("activeBellProfileId"))
                .andExpect(jsonPath("$.errors[0].message").value("Профіль дзвінків не знайдено."));
        assertThat(settings.current()).isEqualTo(original);
    }
}
