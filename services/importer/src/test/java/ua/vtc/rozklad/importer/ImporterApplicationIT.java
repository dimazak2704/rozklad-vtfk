package ua.vtc.rozklad.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
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
    void botServiceMayParseAbsenceText() throws Exception {
        // ендпоінта ще немає: будь-яка відповідь, крім 401/403, означає, що безпека пропустила SERVICE
        mockMvc.perform(post("/api/import/ai/parse-absence").with(token("SERVICE")))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(401, 403));
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
