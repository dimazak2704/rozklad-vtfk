package ua.vtc.rozklad.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ua.vtc.rozklad.core.catalog.ApiTestSupport.id;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.context.WebApplicationContext;
import ua.vtc.rozklad.core.catalog.ApiTestSupport;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.identity.InviteRepository;
import ua.vtc.rozklad.core.identity.InviteStatus;
import ua.vtc.rozklad.core.identity.internal.AccessDtos.AccessDto;
import ua.vtc.rozklad.core.identity.internal.AccessDtos.AccessStatus;
import ua.vtc.rozklad.core.identity.internal.AccessDtos.GrantRequest;
import ua.vtc.rozklad.core.identity.internal.AccessDtos.UpdateRequest;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.InviteKind;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.PasswordLinkDto;
import ua.vtc.rozklad.core.shared.AppProperties;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;
import ua.vtc.rozklad.core.shared.security.CurrentUser;
import ua.vtc.rozklad.core.support.CoreIntegrationTest;
import ua.vtc.rozklad.core.support.TestJwts;

/**
 * «Доступи» against PostgreSQL with an in-memory sign-in service ({@link FakeIdentityProvider}, which keeps the
 * last-admin and revocation rules of {@code auth}): the rules of grants, revocation (D11) and password links.
 */
@CoreIntegrationTest
class AccessServiceIT {

    private static final String PASSWORD = "Long-Enough-Password-1";

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

    private final FakeIdentityProvider auth = new FakeIdentityProvider();
    private AccessService access;
    private InviteService invites;
    private CurrentUser admin;
    private UUID teacher;

    @BeforeEach
    void setUp() throws Exception {
        MockMvc mvc = ApiTestSupport.mockMvc(context);
        invites = new InviteService(inviteRepository, teacherRepository, catalog, auth,
                new IdentityProperties(Duration.ofDays(7), Duration.ofHours(24)), appProperties, clock);
        access = new AccessService(auth, teacherRepository, invites, new AccountLinkLocks(jdbc), clock);
        admin = new CurrentUser(auth.usableAdmin("admin"), "admin", Set.of("ADMIN"));
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
    void aTeacherGrantCreatesAnAccountWithoutAPasswordAndLinksTheTeacher() {
        AccessDto granted = tx(() -> access.grant(teacherGrant("Krentsin@VTC.vn.ua"), "admin"));

        assertThat(granted.email()).isEqualTo("krentsin@vtc.vn.ua");
        assertThat(granted.username()).isNull();
        assertThat(granted.displayName()).isEqualTo("Кренцін Микола Дмитрович");
        assertThat(granted.roles()).containsExactly("TEACHER");
        assertThat(granted.status()).isEqualTo(AccessStatus.ACTIVE);
        assertThat(granted.hasPassword()).isFalse();
        assertThat(granted.teacher().id()).isEqualTo(teacher);
        assertThat(teacherRepository.findById(teacher).orElseThrow().getAccountId()).isEqualTo(granted.id());
        assertThat(access.list("кренцін", null, null)).extracting(AccessDto::id).containsExactly(granted.id());
        assertThat(access.list(null, "ADMIN", null)).extracting(AccessDto::username).containsExactly("admin");
        assertThat(access.list(null, null, AccessStatus.REVOKED)).isEmpty();
    }

    @Test
    void theTeacherRoleAndATeacherComeTogether() {
        assertThatThrownBy(() -> tx(() -> access.grant(new GrantRequest("x@vtc.vn.ua", null, Set.of("TEACHER"), null),
                "admin"))).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> tx(() -> access.grant(new GrantRequest("x@vtc.vn.ua", "X", Set.of("ADMIN"), teacher),
                "admin"))).isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("TEACHER_ROLE_REQUIRED");
        assertThatThrownBy(() -> tx(() -> access.grant(new GrantRequest("x@vtc.vn.ua", null, Set.of("ADMIN"), null),
                "admin"))).isInstanceOf(FieldValidationException.class);
        assertThat(auth.count()).isEqualTo(1);
    }

    @Test
    void anInactiveOrAlreadyLinkedTeacherIsRefused() {
        tx(() -> access.grant(teacherGrant("first@vtc.vn.ua"), "admin"));

        assertConflict(() -> tx(() -> access.grant(teacherGrant("second@vtc.vn.ua"), "admin")),
                "TEACHER_ALREADY_LINKED");
        jdbc.update("UPDATE teacher SET account_id = NULL, active = false WHERE id = ?", teacher);
        assertThatThrownBy(() -> tx(() -> access.grant(teacherGrant("third@vtc.vn.ua"), "admin")))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("TEACHER_INACTIVE");
    }

    @Test
    void anAddressWithAccessCannotBeGrantedTwice() {
        auth.grantedByEmail("taken@vtc.vn.ua", Set.of("TEACHER"));

        assertConflict(() -> tx(() -> access.grant(new GrantRequest("taken@vtc.vn.ua", "Хтось", Set.of("ADMIN"),
                null), "admin")), "EMAIL_TAKEN");
    }

    @Test
    void aFailedTransactionDeletesTheNewAccountAgain() {
        jdbc.update("UPDATE teacher SET account_id = ? WHERE id = ?", UUID.randomUUID(), teacher);
        int before = auth.count();

        // вчитель уже прив'язаний: правило спрацьовує до створення; відкат після створення — нижче
        assertConflict(() -> tx(() -> access.grant(teacherGrant("late@vtc.vn.ua"), "admin")),
                "TEACHER_ALREADY_LINKED");
        jdbc.update("UPDATE teacher SET account_id = NULL WHERE id = ?", teacher);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            access.grant(teacherGrant("late@vtc.vn.ua"), "admin");
            throw new IllegalStateException("commit fails");
        })).hasMessage("commit fails");

        assertThat(auth.count()).isEqualTo(before);
        assertThat(teacherRepository.findById(teacher).orElseThrow().getAccountId()).isNull();
    }

    @Test
    void theLastUsableAdminCannotLoseAccessOrTheRole() {
        UUID other = tx(() -> access.grant(new GrantRequest("boss@vtc.vn.ua", "Директор", Set.of("ADMIN"), null),
                "admin")).id();
        CurrentUser otherAdmin = new CurrentUser(other, "boss@vtc.vn.ua", Set.of("ADMIN"));

        // друге «адмінство» без пароля й Google не рахується: admin — останній, хто може увійти
        assertConflict(() -> tx(() -> access.revoke(admin.id(), otherAdmin)), "LAST_ADMIN");
        assertConflict(() -> tx(() -> access.update(admin.id(), new UpdateRequest(Set.of("TEACHER"), null, teacher),
                "admin")), "LAST_ADMIN");
        auth.linkGoogle(other);
        assertThat(tx(() -> access.revoke(admin.id(), otherAdmin)).status()).isEqualTo(AccessStatus.REVOKED);
    }

    @Test
    void nobodyRevokesTheirOwnAccess() {
        assertThatThrownBy(() -> tx(() -> access.revoke(admin.id(), admin)))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("SELF_REVOKE");
    }

    @Test
    void revokingRevokesOpenInvitesAndPasswordLinksAndOnlyRestoreBringsAccessBack() {
        AccessDto granted = tx(() -> access.grant(teacherGrant("krentsin@vtc.vn.ua"), "admin"));
        String invite = token(tx(() -> invites.create(teacher, "admin")).url());
        String link = token(tx(() -> access.passwordLink(granted.id(), "admin")).url());

        AccessDto revoked = tx(() -> access.revoke(granted.id(), admin));

        assertThat(revoked.status()).isEqualTo(AccessStatus.REVOKED);
        assertThat(revoked.teacher().id()).as("the teacher link stays").isEqualTo(teacher);
        assertThat(invites.inspect(invite).status()).isEqualTo(InviteStatus.REVOKED);
        assertThat(invites.inspect(link).status()).isEqualTo(InviteStatus.REVOKED);
        // старе запрошення відповідає саме «Доступ відкликано», а не «надіслали новіше»
        assertThatThrownBy(() -> tx(() -> invites.accept(invite, "krentsin", PASSWORD)))
                .isInstanceOf(ErrorResponseException.class)
                .satisfies(e -> assertThat(((ErrorResponseException) e).getBody().getDetail())
                        .isEqualTo("Доступ відкликано. Зверніться до навчальної частини."));
        assertConflict(() -> tx(() -> access.passwordLink(granted.id(), "admin")), "ACCOUNT_REVOKED");

        // нове запрошення не повертає доступ: 410 «Доступ відкликано», запрошення відкликано
        String fresh = token(tx(() -> invites.create(teacher, "admin")).url());
        assertGone(() -> tx(() -> invites.accept(fresh, "krentsin", PASSWORD)));
        assertThat(auth.user(granted.id()).orElseThrow().enabled()).isFalse();

        assertThat(tx(() -> access.restore(granted.id(), "admin")).status()).isEqualTo(AccessStatus.ACTIVE);
    }

    @Test
    void aPasswordLinkGivesAnAccountWithoutAUsernameItsChoiceAndAPassword() {
        AccessDto granted = tx(() -> access.grant(teacherGrant("krentsin@vtc.vn.ua"), "admin"));
        PasswordLinkDto older = tx(() -> access.passwordLink(granted.id(), "admin"));
        PasswordLinkDto link = tx(() -> access.passwordLink(granted.id(), "admin"));

        assertThat(link.url()).startsWith("http://localhost:5180/password/");
        assertThat(invites.inspect(token(older.url())).status()).as("a newer link revokes the older")
                .isEqualTo(InviteStatus.REVOKED);
        var info = invites.inspect(token(link.url()));
        assertThat(info.kind()).isEqualTo(InviteKind.PASSWORD);
        assertThat(info.valid()).isTrue();
        assertThat(info.teacherName()).isEqualTo("Кренцін Микола Дмитрович");
        assertThat(info.username()).isNull();

        tx(() -> invites.accept(token(link.url()), "Krentsin", PASSWORD));

        var account = auth.user(granted.id()).orElseThrow();
        assertThat(account.username()).isEqualTo("krentsin");
        assertThat(account.password()).isEqualTo(PASSWORD);
        assertThat(invites.inspect(token(link.url())).status()).isEqualTo(InviteStatus.USED);
        assertThat(invites.list(null)).as("password links are not teacher invites").isEmpty();
    }

    @Test
    void aPasswordLinkKeepsAnExistingUsername() {
        UUID account = auth.existingUser("nepyivoda", "Old-Password-2026", true);
        String link = token(tx(() -> access.passwordLink(account, "admin")).url());
        assertThat(invites.inspect(link).username()).isEqualTo("nepyivoda");

        assertThatThrownBy(() -> tx(() -> invites.accept(link, "someone", PASSWORD)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("nepyivoda");
        tx(() -> invites.accept(link, "nepyivoda", PASSWORD));

        assertThat(auth.user(account).orElseThrow().password()).isEqualTo(PASSWORD);
    }

    @Test
    void anAccountRevokedAfterTheLinkWasMadeIsNotReEnabledByIt() {
        AccessDto granted = tx(() -> access.grant(new GrantRequest("boss@vtc.vn.ua", "Директор", Set.of("ADMIN"),
                null), "admin"));
        String link = token(tx(() -> access.passwordLink(granted.id(), "admin")).url());
        auth.revoke(granted.id());

        assertGone(() -> tx(() -> invites.accept(link, "boss", PASSWORD)));

        assertThat(auth.user(granted.id()).orElseThrow().enabled()).isFalse();
        assertThat(invites.inspect(link).status()).isEqualTo(InviteStatus.REVOKED);
    }

    @Test
    void updateChangesRolesNameAndRelinksTheTeacher() {
        AccessDto granted = tx(() -> access.grant(new GrantRequest("boss@vtc.vn.ua", "Директор", Set.of("ADMIN"),
                null), "admin"));

        AccessDto changed = tx(() -> access.update(granted.id(),
                new UpdateRequest(Set.of("ADMIN", "TEACHER"), "Кренцін М. Д.", teacher), "admin"));

        assertThat(changed.roles()).containsExactly("ADMIN", "TEACHER");
        assertThat(changed.displayName()).isEqualTo("Кренцін М. Д.");
        assertThat(changed.teacher().id()).isEqualTo(teacher);
        AccessDto unlinked = tx(() -> access.update(granted.id(), new UpdateRequest(Set.of("ADMIN"), null, null),
                "admin"));
        assertThat(unlinked.teacher()).isNull();
        assertThat(teacherRepository.findById(teacher).orElseThrow().getAccountId()).isNull();
    }

    @Test
    void anUnreachableSignInServiceIs503() {
        auth.goDown();

        assertThatThrownBy(() -> access.list(null, null, null)).isInstanceOf(ErrorResponseException.class)
                .extracting(e -> ((ErrorResponseException) e).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThatThrownBy(() -> tx(() -> access.grant(new GrantRequest("x@vtc.vn.ua", "X", Set.of("ADMIN"), null),
                "admin"))).isInstanceOf(ErrorResponseException.class);
    }

    private GrantRequest teacherGrant(String email) {
        return new GrantRequest(email, null, Set.of("TEACHER"), teacher);
    }

    private <T> T tx(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private static String token(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private static void assertConflict(Runnable call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(ErrorResponseException.class).satisfies(e -> {
            var problem = ((ErrorResponseException) e).getBody();
            assertThat(problem.getStatus()).isEqualTo(409);
            assertThat(problem.getProperties()).containsEntry("code", code);
        });
    }

    private static void assertGone(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(ErrorResponseException.class)
                .satisfies(e -> assertThat(((ErrorResponseException) e).getStatusCode().value()).isEqualTo(410));
    }
}
