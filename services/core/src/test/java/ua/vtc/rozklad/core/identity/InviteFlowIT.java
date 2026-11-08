package ua.vtc.rozklad.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.vtc.rozklad.core.catalog.ApiTestSupport.id;
import static ua.vtc.rozklad.core.catalog.ApiTestSupport.read;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import ua.vtc.rozklad.core.catalog.ApiTestSupport;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.identity.internal.FakeIdentityProvider;
import ua.vtc.rozklad.core.identity.internal.IdentityProperties;
import ua.vtc.rozklad.core.identity.internal.InviteService;
import ua.vtc.rozklad.core.shared.AppProperties;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.support.TestJwts;

/**
 * Invite flow against PostgreSQL. HTTP covers what happens before the identity provider (404, 410,
 * public info, admin list); account creation runs through {@link InviteService} wired with an
 * in-memory Keycloak in a real transaction, so the shared test context needs no Keycloak.
 */
@CoreIntegrationTest
class InviteFlowIT {

    private static final String CREDENTIALS = "{\"username\":\"krentsin\",\"password\":\"secret-password\"}";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private InviteRepository inviteRepository;

    @Autowired
    private TeacherRepository teacherRepository;

    @Autowired
    private CatalogQueries catalog;

    @Autowired
    private AppProperties appProperties;

    @Autowired
    private Clock clock;

    private final FakeIdentityProvider keycloak = new FakeIdentityProvider();
    private InviteService withFakeKeycloak;
    private MockMvc mvc;
    private UUID teacher;

    @BeforeEach
    void setUp() throws Exception {
        mvc = ApiTestSupport.mockMvc(context);
        withFakeKeycloak = new InviteService(inviteRepository, teacherRepository, catalog, keycloak,
                new IdentityProperties(Duration.ofDays(7)), appProperties, clock);
        teacher = id(mvc.perform(post("/api/admin/teachers").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastName\":\"Кренцін\",\"firstName\":\"Микола\",\"middleName\":\"Дмитрович\"}"))
                .andExpect(status().isCreated()).andReturn());
    }

    @AfterEach
    void cleanUp() {
        ApiTestSupport.cleanDatabase(jdbc);
    }

    @Test
    void teacherRegistersOnceWithTheLinkAndSeesHimselfInMe() throws Exception {
        String token = createInvite();
        mvc.perform(get("/api/public/invites/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.teacherName").value("Кренцін Микола Дмитрович"));

        transaction.executeWithoutResult(status -> withFakeKeycloak.accept(token, "Krentsin", "secret-password"));

        UUID account = keycloak.byUsername("krentsin").orElseThrow().id();
        assertThat(keycloak.user(account).orElseThrow().roles()).containsExactly("TEACHER");
        assertThat(teacherRepository.findById(teacher).orElseThrow().getKeycloakUserId()).isEqualTo(account);
        mvc.perform(get("/api/me").with(TestJwts.user(account, "krentsin", "TEACHER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("krentsin"))
                .andExpect(jsonPath("$.teacher.id").value(teacher.toString()))
                .andExpect(jsonPath("$.teacher.shortName").value("Кренцін М.Д."));

        accept(token)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.inviteStatus").value("USED"));
        mvc.perform(get("/api/public/invites/{token}", token))
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.status").value("USED"));
        mvc.perform(get("/api/admin/teachers/{id}", teacher).with(TestJwts.admin()))
                .andExpect(jsonPath("$.linked").value(true));
        mvc.perform(get("/api/internal/teachers/{id}", teacher).with(TestJwts.service()))
                .andExpect(jsonPath("$.keycloakUserId").value(account.toString()));
    }

    @Test
    void newerInviteRevokesTheOlderOne() throws Exception {
        String older = createInvite();
        String newer = createInvite();

        accept(older)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("urn:rozklad:problem:invite-gone"))
                .andExpect(jsonPath("$.status").value(410))
                .andExpect(jsonPath("$.inviteStatus").value("REVOKED"));
        mvc.perform(get("/api/admin/invites").param("teacherId", teacher.toString()).with(TestJwts.admin()))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].teacher.shortName").value("Кренцін М.Д."))
                .andExpect(jsonPath("$[1].status").value("REVOKED"))
                .andExpect(jsonPath("$[0].token").doesNotExist());
        assertThat(newer).isNotEqualTo(older);
    }

    @Test
    void expiredAndUnknownLinks() throws Exception {
        String token = createInvite();
        jdbc.update("UPDATE invite SET expires_at = now() - interval '1 minute'");

        accept(token)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.inviteStatus").value("EXPIRED"))
                .andExpect(jsonPath("$.detail").value("Термін дії запрошення минув. Попросіть адміністратора надіслати нове."));
        accept("A".repeat(43)).andExpect(status().isNotFound());
        accept("not-a-token").andExpect(status().isNotFound());
        mvc.perform(get("/api/public/invites/{token}", "B".repeat(43))).andExpect(status().isNotFound());
    }

    @Test
    void takenUsernameLeavesTheInviteUsable() throws Exception {
        keycloak.existingUser("krentsin", "someone-else", true);
        String token = createInvite();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                withFakeKeycloak.accept(token, "krentsin", "secret-password")))
                .isInstanceOf(ConflictException.class);

        mvc.perform(get("/api/public/invites/{token}", token)).andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(teacherRepository.findById(teacher).orElseThrow().getKeycloakUserId()).isNull();
    }

    @Test
    void onlyAdminsCreateInvites() throws Exception {
        mvc.perform(post("/api/admin/invites").with(TestJwts.teacher())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"teacherId\":\"%s\"}".formatted(teacher)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/invites").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"teacherId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("teacherId"));
    }

    private String createInvite() throws Exception {
        return read(mvc.perform(post("/api/admin/invites").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"teacherId\":\"%s\"}".formatted(teacher)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.startsWith("http://localhost:5180/invite/")))
                .andReturn(), "$.token");
    }

    private org.springframework.test.web.servlet.ResultActions accept(String token) throws Exception {
        return mvc.perform(post("/api/public/invites/{token}/accept", token)
                .contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS));
    }
}
