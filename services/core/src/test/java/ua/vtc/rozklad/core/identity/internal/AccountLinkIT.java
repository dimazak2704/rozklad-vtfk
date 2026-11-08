package ua.vtc.rozklad.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.vtc.rozklad.core.catalog.ApiTestSupport.id;

import java.time.Clock;
import java.util.List;
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
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.identity.InviteRepository;
import ua.vtc.rozklad.core.identity.internal.TeacherAccountService.TeacherAccountDto;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.substitution.fixture.Parallel;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.support.TestJwts;

/**
 * Linking accounts in parallel against PostgreSQL (the unique {@code keycloak_user_id} index is the
 * last guard, the advisory locks of {@link AccountLinkLocks} the first): a double click on «Прив'язати»
 * for one teacher is idempotent, a race with another teacher is a plain 409 «уже прив'язано».
 */
@CoreIntegrationTest
class AccountLinkIT {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private TeacherRepository teachers;

    @Autowired
    private InviteRepository invites;

    @Autowired
    private Clock clock;

    private final FakeIdentityProvider keycloak = new FakeIdentityProvider();
    private TeacherAccountService service;
    private UUID stets;
    private UUID hanza;

    @BeforeEach
    void setUp() throws Exception {
        MockMvc mvc = ApiTestSupport.mockMvc(context);
        service = new TeacherAccountService(teachers, invites, keycloak, new AccountLinkLocks(jdbc), clock);
        stets = teacher(mvc, "Стець");
        hanza = teacher(mvc, "Ганза");
        keycloak.existingUser("r4-stets", "secret-password", true);
    }

    @AfterEach
    void cleanUp() {
        ApiTestSupport.cleanDatabase(jdbc);
    }

    @Test
    void parallelLinksOfOneAccountAreIdempotentForItsTeacherAndAConflictForAnother() throws Exception {
        List<Object> results = Parallel.run(() -> link(stets), () -> link(hanza), () -> link(stets),
                () -> link(hanza));

        UUID owner = jdbc.queryForObject("SELECT id FROM teacher WHERE keycloak_user_id IS NOT NULL", UUID.class);
        UUID loser = owner.equals(stets) ? hanza : stets;
        List<Object> ownerResults = List.of(results.get(owner.equals(stets) ? 0 : 1),
                results.get(owner.equals(stets) ? 2 : 3));
        List<Object> loserResults = List.of(results.get(loser.equals(stets) ? 0 : 1),
                results.get(loser.equals(stets) ? 2 : 3));
        assertThat(ownerResults).allSatisfy(result -> assertThat(result).isInstanceOfSatisfying(
                TeacherAccountDto.class, dto -> assertThat(dto.username()).isEqualTo("r4-stets")));
        assertThat(loserResults).allSatisfy(result -> assertThat(result).isInstanceOfSatisfying(
                ConflictException.class, error -> assertThat(error.getMessage())
                        .contains("уже прив'язано до викладача")));
    }

    private Object link(UUID teacher) {
        return transaction.execute(status -> service.link(teacher, "r4-stets", "admin"));
    }

    private static UUID teacher(MockMvc mvc, String lastName) throws Exception {
        return id(mvc.perform(post("/api/admin/teachers").with(TestJwts.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastName\":\"%s\",\"firstName\":\"Олег\"}".formatted(lastName)))
                .andExpect(status().isCreated()).andReturn());
    }
}
