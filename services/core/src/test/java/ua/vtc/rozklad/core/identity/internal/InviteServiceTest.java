package ua.vtc.rozklad.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.ErrorResponseException;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.identity.Invite;
import ua.vtc.rozklad.core.identity.InviteRepository;
import ua.vtc.rozklad.core.identity.InviteStatus;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.Account;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.AccountRevokedException;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.InviteCreatedDto;
import ua.vtc.rozklad.core.shared.AppProperties;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.security.Roles;

/** Invite flow rules (ARCHITECTURE §6) with an in-memory identity provider. */
class InviteServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

    private final InviteRepository invites = mock(InviteRepository.class);
    private final TeacherRepository teachers = mock(TeacherRepository.class);
    private final FakeIdentityProvider auth = new FakeIdentityProvider();
    private final InviteService service = new InviteService(invites, teachers, mock(CatalogQueries.class), auth,
            new IdentityProperties(Duration.ofDays(7)), new AppProperties(URI.create("http://localhost:5180")),
            Clock.fixed(NOW, ZoneId.of("Europe/Kyiv")));

    private Teacher teacher;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        teacher = new Teacher("Кренцін", "Кренцін М.Д.", "Кренцін");
        teacher.setFirstName("Микола");
        given(teachers.findById(teacher.getId())).willReturn(Optional.of(teacher));
        given(invites.save(any(Invite.class))).willAnswer(call -> call.getArgument(0));
        given(invites.findAllByTeacherIdAndUsedAtIsNullAndRevokedAtIsNull(any())).willReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void createStoresOnlyTheHashAndRevokesOlderInvites() {
        Invite older = new Invite(teacher.getId(), "a".repeat(64), NOW.plusSeconds(60), "admin");
        given(invites.findAllByTeacherIdAndUsedAtIsNullAndRevokedAtIsNull(teacher.getId())).willReturn(List.of(older));

        InviteCreatedDto created = service.create(teacher.getId(), "admin");

        assertThat(created.token()).matches("[A-Za-z0-9_-]{43}");
        assertThat(created.url()).isEqualTo("http://localhost:5180/invite/" + created.token());
        assertThat(created.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(older.status(NOW)).isEqualTo(InviteStatus.REVOKED);
        org.mockito.Mockito.verify(invites).save(org.mockito.ArgumentMatchers.argThat(invite ->
                invite.getTokenHash().equals(InviteTokens.hash(created.token()))
                        && !invite.getTokenHash().contains(created.token())));
    }

    @Test
    void inactiveOrUnknownTeacherGetsNoInvite() {
        teacher.setActive(false);
        assertThatThrownBy(() -> service.create(teacher.getId(), "admin"))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        error -> assertThat(error.getCode()).isEqualTo("TEACHER_INACTIVE"));

        UUID unknown = UUID.randomUUID();
        given(teachers.findById(unknown)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(unknown, "admin")).isInstanceOf(FieldValidationException.class);
    }

    @Test
    void acceptCreatesTheAccountWithTeacherRoleAndLinksIt() {
        String token = activeInvite();
        Invite another = new Invite(teacher.getId(), "b".repeat(64), NOW.plusSeconds(600), "admin");
        given(invites.findAllByTeacherIdAndUsedAtIsNullAndRevokedAtIsNull(teacher.getId())).willReturn(List.of(another));

        assertThat(service.accept(token, " Krentsin ", "secret-password").username()).isEqualTo("krentsin");

        FakeIdentityProvider.User user = auth.byUsername("krentsin").orElseThrow();
        assertThat(user.roles()).containsExactly(Roles.TEACHER);
        assertThat(user.password()).isEqualTo("secret-password");
        assertThat(user.displayName()).isEqualTo("Кренцін Микола");
        assertThat(user.email()).isNull();
        assertThat(teacher.getAccountId()).isEqualTo(user.id());
        assertThat(stored(token).status(NOW)).isEqualTo(InviteStatus.USED);
        assertThat(another.status(NOW)).isEqualTo(InviteStatus.REVOKED);
    }

    @Test
    void expiredUsedAndRevokedInvitesAreGone() {
        String expired = invite(NOW.minusSeconds(1));
        assertGone(expired, InviteStatus.EXPIRED);

        String used = activeInvite();
        stored(used).markUsed(NOW.minusSeconds(60));
        assertGone(used, InviteStatus.USED);

        String revoked = activeInvite();
        stored(revoked).revoke(NOW.minusSeconds(60));
        assertGone(revoked, InviteStatus.REVOKED);

        assertThat(auth.count()).isZero();
    }

    @Test
    void unknownOrMalformedTokenIsNotFound() {
        assertThatThrownBy(() -> service.accept("not-a-token", "krentsin", "secret-password"))
                .isInstanceOf(NotFoundException.class);
        String unknown = InviteTokens.newToken();
        given(invites.lockByTokenHash(InviteTokens.hash(unknown))).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.accept(unknown, "krentsin", "secret-password"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void takenUsernameIsAConflictAndTheInviteStaysUsable() {
        auth.existingUser("krentsin", "other", true);
        String token = activeInvite();

        assertThatThrownBy(() -> service.accept(token, "krentsin", "secret-password"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("«krentsin» уже зайняте");
        assertThat(stored(token).status(NOW)).isEqualTo(InviteStatus.ACTIVE);
        assertThat(teacher.getAccountId()).isNull();
    }

    @Test
    void linkedTeacherGetsAccessBackUnderTheSameUsername() {
        UUID account = auth.existingUser("krentsin", "forgotten", false);
        teacher.setAccountId(account);
        String token = activeInvite();

        service.accept(token, "krentsin", "new-password");

        assertThat(auth.user(account)).hasValueSatisfying(user -> {
            assertThat(user.enabled()).isTrue();
            assertThat(user.password()).isEqualTo("new-password");
            assertThat(user.roles()).contains(Roles.TEACHER);
        });
        assertThat(auth.count()).isEqualTo(1);
    }

    @Test
    void linkedTeacherCannotTakeAnotherUsername() {
        teacher.setAccountId(auth.existingUser("krentsin", "forgotten", true));
        String token = activeInvite();

        assertThatThrownBy(() -> service.accept(token, "mykola", "new-password"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("«krentsin»");
    }

    @Test
    void accountDeletedInAuthIsCreatedAgain() {
        UUID deleted = UUID.randomUUID();
        teacher.setAccountId(deleted);
        String token = activeInvite();

        service.accept(token, "krentsin", "new-password");

        assertThat(teacher.getAccountId()).isNotEqualTo(deleted)
                .isEqualTo(auth.byUsername("krentsin").orElseThrow().id());
    }

    @Test
    void signInServiceDownIsServiceUnavailable() {
        auth.goDown();
        String token = activeInvite();

        assertThatThrownBy(() -> service.accept(token, "krentsin", "secret-password"))
                .isInstanceOfSatisfying(ErrorResponseException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(stored(token).status(NOW)).isEqualTo(InviteStatus.ACTIVE);
    }

    @Test
    void createdAccountIsRemovedWhenTheTransactionDoesNotCommit() {
        // auth зберіг запис, але відповідь не дійшла: компенсація все одно знає його id
        auth.failAfterNextCreate();
        String token = activeInvite();

        assertThatThrownBy(() -> service.accept(token, "krentsin", "secret-password"))
                .isInstanceOf(ErrorResponseException.class);
        assertThat(auth.count()).isEqualTo(1);

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(auth.count()).isZero();
    }

    @Test
    void accessGrantedByEmailGetsTheChosenUsernameAndPassword() {
        UUID account = auth.grantedByEmail("krentsin@college.example", Set.of(Roles.TEACHER));
        teacher.setAccountId(account);
        String token = activeInvite();

        assertThat(service.accept(token, "Krentsin", "secret-password").username()).isEqualTo("krentsin");

        assertThat(auth.user(account)).hasValueSatisfying(user -> {
            assertThat(user.username()).isEqualTo("krentsin");
            assertThat(user.email()).isEqualTo("krentsin@college.example");
            assertThat(user.password()).isEqualTo("secret-password");
            assertThat(user.enabled()).isTrue();
        });
        assertThat(auth.count()).isEqualTo(1);
        assertThat(stored(token).status(NOW)).isEqualTo(InviteStatus.USED);
    }

    @Test
    void aRevokedAccountIsNotEnabledByAnInviteAndTheInviteIsRevoked() {
        UUID account = auth.existingUser("krentsin", "old-password", true);
        auth.revoke(account);
        teacher.setAccountId(account);
        String token = activeInvite();

        assertThatThrownBy(() -> service.accept(token, "krentsin", "secret-password"))
                .isInstanceOfSatisfying(IdentityProblems.AccessRevokedException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.GONE);
                    assertThat(error.getBody().getProperties()).containsEntry("inviteStatus", InviteStatus.REVOKED);
                    assertThat(error.getBody().getDetail())
                            .isEqualTo("Доступ відкликано. Зверніться до навчальної частини.");
                });
        assertThat(auth.user(account)).hasValueSatisfying(user -> {
            assertThat(user.enabled()).isFalse();
            assertThat(user.password()).isEqualTo("old-password");
        });
        assertThat(stored(token).status(NOW)).isEqualTo(InviteStatus.REVOKED);
    }

    @Test
    void aRevocationRacingTheInviteStillWins() throws NoSuchMethodException {
        UUID account = auth.existingUser("krentsin", "old-password", false);
        teacher.setAccountId(account);
        String token = activeInvite();
        // відкликали після findUser: auth відмовляє в enable (409 account-revoked)
        FakeIdentityProvider racing = new FakeIdentityProvider() {
            @Override
            public Optional<Account> findUser(UUID userId) {
                return auth.findUser(userId);
            }

            @Override
            public void restoreAccess(UUID userId, String password) {
                throw new AccountRevokedException(userId);
            }
        };
        InviteService withRace = new InviteService(invites, teachers, mock(CatalogQueries.class), racing,
                new IdentityProperties(Duration.ofDays(7)), new AppProperties(URI.create("http://localhost:5180")),
                Clock.fixed(NOW, ZoneId.of("Europe/Kyiv")));

        assertThatThrownBy(() -> withRace.accept(token, "krentsin", "secret-password"))
                .isInstanceOf(IdentityProblems.AccessRevokedException.class);
        assertThat(stored(token).status(NOW)).isEqualTo(InviteStatus.REVOKED);
        // відмова цього типу не відкочує транзакцію: відкликання запрошення зберігається
        Transactional accept = InviteService.class.getMethod("accept", String.class, String.class, String.class)
                .getAnnotation(Transactional.class);
        assertThat(accept.noRollbackFor()).containsExactly(IdentityProblems.AccessRevokedException.class);
    }

    @Test
    void passwordRejectedBySignInServiceExplainsTheRule() {
        auth.rejectPasswords("too-common");
        String token = activeInvite();

        assertThatThrownBy(() -> service.accept(token, "krentsin", "password1234"))
                .isInstanceOfSatisfying(FieldValidationException.class, error -> assertThat(error.getErrors())
                        .singleElement().satisfies(field -> assertThat(field.toString())
                                .contains("Цей пароль надто поширений. Виберіть інший.")));
        assertThat(stored(token).status(NOW)).isEqualTo(InviteStatus.ACTIVE);
        assertThat(auth.count()).isZero();
        assertThat(InviteService.passwordRejected("too-short")).isEqualTo("Пароль має містити щонайменше 12 символів.");
        assertThat(InviteService.passwordRejected("too-long")).contains("64").contains("36");
        assertThat(InviteService.passwordRejected("same-as-username"))
                .isEqualTo("Пароль не може збігатися з ім'ям користувача.");
        assertThat(InviteService.passwordRejected("something-new"))
                .isEqualTo("Пароль не відповідає правилам безпеки сервера входу.");
    }

    @Test
    void passwordEqualToUsernameIsRejected() {
        String token = activeInvite();

        assertThatThrownBy(() -> service.accept(token, "krentsin", "KRENTSIN"))
                .isInstanceOf(FieldValidationException.class);
    }

    private void assertGone(String token, InviteStatus status) {
        assertThatThrownBy(() -> service.accept(token, "krentsin", "secret-password"))
                .isInstanceOfSatisfying(ErrorResponseException.class, error -> {
                    assertThat(error.getStatusCode()).isEqualTo(HttpStatus.GONE);
                    assertThat(error.getBody().getProperties()).containsEntry("inviteStatus", status);
                });
    }

    private String activeInvite() {
        return invite(NOW.plus(Duration.ofDays(7)));
    }

    private String invite(Instant expiresAt) {
        String token = InviteTokens.newToken();
        Invite invite = new Invite(teacher.getId(), InviteTokens.hash(token), expiresAt, "admin");
        given(invites.lockByTokenHash(InviteTokens.hash(token))).willReturn(Optional.of(invite));
        given(invites.findByTokenHash(InviteTokens.hash(token))).willReturn(Optional.of(invite));
        return token;
    }

    private Invite stored(String token) {
        return invites.lockByTokenHash(InviteTokens.hash(token)).orElseThrow();
    }
}
