package ua.vtc.rozklad.core.catalog;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import ua.vtc.rozklad.core.support.ReferenceData;

/**
 * Helpers for the API integration tests of catalog, calendar, load and identity. They all run in
 * the shared {@code @CoreIntegrationTest} context (one set of containers); MockMvc is built on that
 * context with the production security filter chain, so {@code TestJwts} tokens work without
 * Keycloak.
 */
public final class ApiTestSupport {

    /** Everything these tests create; reference data (bell profiles, shift windows, settings) stays. */
    private static final String TRUNCATE = """
            TRUNCATE invite, load_item_teacher, load_item, calendar_period_group, calendar_period, week_parity_override,
                     semester, academic_year, subgroup, student_group, teacher_discipline, teacher_alias, teacher,
                     discipline_alias, discipline, room, building, specialty, department, cycle_commission CASCADE
            """;

    private ApiTestSupport() {
    }

    public static MockMvc mockMvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .defaultResponseCharacterEncoding(StandardCharsets.UTF_8)
                .build();
    }

    public static void cleanDatabase(JdbcTemplate jdbc) {
        jdbc.execute(TRUNCATE);
        ReferenceData.restore(jdbc);
    }

    public static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    public static UUID id(MvcResult result) throws Exception {
        return UUID.fromString(JsonPath.read(body(result), "$.id"));
    }

    public static <T> T read(MvcResult result, String path) throws Exception {
        return JsonPath.read(body(result), path);
    }
}
