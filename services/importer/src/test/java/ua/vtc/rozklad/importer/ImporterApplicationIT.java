package ua.vtc.rozklad.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import ua.vtc.rozklad.importer.config.AiProperties;
import ua.vtc.rozklad.importer.config.KeycloakRealmRoleConverter;
import ua.vtc.rozklad.importer.support.TestcontainersConfiguration;

/** Smoke test: the importer boots against real PostgreSQL and Redis with its security rules. */
@SpringBootTest(properties = "app.ai.api-key=")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ImporterApplicationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AiProperties aiProperties;

    @Autowired
    private RestClient coreRestClient;

    @Test
    void healthIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /** One error shape: the request field, no Java method names, the firewall's rejections as ProblemDetail. */
    @Test
    void frameworkErrorsNameTheRequestField() throws Exception {
        mockMvc.perform(get("/api/import/jobs").param("size", "100000").with(token("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        mockMvc.perform(get("/api/import/jobs").param("page", "-1").with(token("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("page"));
        mockMvc.perform(multipart("/api/import/jobs").param("kind", "TIMETABLE_XLSX").with(token("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("file"));
        mockMvc.perform(get(java.net.URI.create("/api/import/x/../jobs")).with(token("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:bad-request"));
    }

    /** {@code createNew} is a JSON tree, not bound through the String deserializer: NUL is checked by hand. */
    @Test
    void nulInANewCatalogEntryIsAFieldError() throws Exception {
        mockMvc.perform(put("/api/import/jobs/{id}/mappings", UUID.randomUUID()).with(token("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mappings\":[{\"type\":\"TEACHER\",\"raw\":\"Хтось\","
                                + "\"createNew\":{\"lastName\":\"R5\\u0000x\"},\"addAlias\":false}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("mappings[0].createNew.lastName"))
                .andExpect(jsonPath("$.errors[0].message").value("Текст містить недопустимий символ NUL (U+0000)."));
    }

    @Test
    void importApiIsForAdminsOnly() throws Exception {
        mockMvc.perform(get("/api/import/jobs"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(jsonPath("$.title").value("Потрібна автентифікація"));
        mockMvc.perform(get("/api/import/jobs").with(token("SERVICE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void botServiceParsesAbsenceTextByRulesWhenAiIsDisabled() throws Exception {
        mockMvc.perform(post("/api/import/ai/parse-absence").with(token("SERVICE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"з 4 по 6 листопада відрядження, 1-2 пари\",\"today\":\"2026-11-07\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateFrom").value("2026-11-04"))
                .andExpect(jsonPath("$.dateTo").value("2026-11-06"))
                .andExpect(jsonPath("$.pairs[1]").value(2))
                .andExpect(jsonPath("$.reasonCategory").value("BUSINESS_TRIP"))
                .andExpect(jsonPath("$.source").value("RULES"));
        // правила не впевнені, а ШІ вимкнено — відповідь усе одно є, з проханням уточнити
        mockMvc.perform(post("/api/import/ai/parse-absence").with(token("SERVICE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"не зможу провести пари\",\"today\":\"2026-11-07\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dateFrom").value("2026-11-07"))
                .andExpect(jsonPath("$.clarification").isNotEmpty());
    }

    @Test
    void parseAbsenceValidatesInputAndRejectsTeachers() throws Exception {
        mockMvc.perform(post("/api/import/ai/parse-absence").with(token("SERVICE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("text"))
                .andExpect(jsonPath("$.errors[1].field").value("today"));
        mockMvc.perform(post("/api/import/ai/parse-absence").with(token("SERVICE"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"завтра\",\"today\":\"2026-13-40\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:validation"))
                .andExpect(jsonPath("$.errors[0].field").value("today"))
                .andExpect(jsonPath("$.errors[0].message").value("Очікується дата у форматі РРРР-ММ-ДД."));
        // NUL (U+0000) PostgreSQL не збереже: 400 із полем, як у core, а не 409 «запис уже існує»
        mockMvc.perform(post("/api/import/ai/parse-absence").with(token("SERVICE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"a\\u0000b\",\"today\":\"2026-11-07\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("text"))
                .andExpect(jsonPath("$.errors[0].message").value("Текст містить недопустимий символ NUL (U+0000)."));
        mockMvc.perform(post("/api/import/ai/parse-absence").with(token("TEACHER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"завтра\",\"today\":\"2026-11-07\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/import/ai/usage").with(token("SERVICE"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/import/ai/usage").with(token("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.model.text").value("claude-haiku-5-5"));
    }

    @Test
    void secretsAreNeverPrintedAndClientsAreWired() {
        assertThat(aiProperties.enabled()).isFalse();
        assertThat(aiProperties.toString()).doesNotContain("sk-");
        assertThat(coreRestClient).isNotNull();
    }

    private static JwtRequestPostProcessor token(String role) {
        return jwt().jwt(token -> token.claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(new KeycloakRealmRoleConverter());
    }
}
