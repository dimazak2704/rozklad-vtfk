package ua.vtc.rozklad.auth.selfservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;
import org.springframework.session.Session;
import ua.vtc.rozklad.auth.account.Account;
import ua.vtc.rozklad.auth.account.AccountRepository;
import ua.vtc.rozklad.auth.account.PasswordPolicy;
import ua.vtc.rozklad.auth.config.AuthServerProperties;
import ua.vtc.rozklad.auth.login.ClientAddress;
import ua.vtc.rozklad.auth.login.LockoutPolicy;
import ua.vtc.rozklad.auth.login.LoginAttempts;
import ua.vtc.rozklad.auth.selfservice.OwnPasswordRefusal.Problem;
import ua.vtc.rozklad.auth.session.GoogleSignInMark;
import ua.vtc.rozklad.auth.support.PropertiesFixture;

class OwnPasswordServiceTest {

    private static final UUID ID = UUID.fromString("a0000000-0000-4000-8000-000000000002");
    private static final UUID OTHER = UUID.fromString("a0000000-0000-4000-8000-000000000003");
    private static final String CURRENT = "Current-Password-2026";
    private static final String NEW = "Brand-New-Password-7";
    private static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");
    private static final ClientAddress ADDRESS = ClientAddress.parse("198.51.100.7");
    private static final LockoutPolicy.Stats CLEAN = new LockoutPolicy.Stats(0, null, 0, 0, false);

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final LoginAttempts attempts = mock(LoginAttempts.class);
    @SuppressWarnings("unchecked")
    private final FindByIndexNameSessionRepository<Session> sessions = mock(FindByIndexNameSessionRepository.class);
    private final AuthServerProperties properties = PropertiesFixture.dev();
    private final byte[] identifier = LoginAttempts.hash("krentsin");
    private OwnPasswordService service;

    @BeforeEach
    void setUp() {
        PasswordPolicy policy = new PasswordPolicy(properties);
        service = new OwnPasswordService(accounts, policy, encoder, attempts, properties, sessions,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(attempts.stats(any(), any(), isNull(), eq(NOW), any())).thenReturn(CLEAN);
    }

    @Test
    void theStatusSaysWhetherThereIsAPasswordToChange() {
        givenAccount(teacher(encoder.encode(CURRENT), false, true));
        assertThat(service.info(ID)).isEqualTo(new OwnPasswordService.Info(OwnPasswordService.Status.CHANGEABLE,
                properties.password().minLength(), PasswordPolicy.MAX_LENGTH, false));

        givenAccount(teacher(null, false, true));
        assertThat(service.info(ID).status()).isEqualTo(OwnPasswordService.Status.NO_PASSWORD);

        givenAccount(teacher(encoder.encode(CURRENT), true, true));
        assertThat(service.info(ID).status()).isEqualTo(OwnPasswordService.Status.DEMO);
    }

    @Test
    void aChangeStoresTheNewPasswordAndEndsEveryOtherSessionButThisOne() {
        givenAccount(teacher(encoder.encode(CURRENT), false, true));
        Map<String, Session> open = new LinkedHashMap<>();
        open.put("this-browser", new MapSession("this-browser"));
        open.put("phone", new MapSession("phone"));
        open.put("laptop", new MapSession("laptop"));
        when(sessions.findByPrincipalName(ID.toString())).thenReturn(open);

        int ended = service.change(ID, CURRENT, NEW, ADDRESS, "this-browser");

        assertThat(ended).isEqualTo(2);
        verify(sessions).deleteById("phone");
        verify(sessions).deleteById("laptop");
        verify(sessions, never()).deleteById("this-browser");
        var hash = ArgumentCaptor.forClass(String.class);
        verify(accounts).setPassword(eq(ID), hash.capture(), eq(NOW), eq("krentsin"));
        assertThat(encoder.matches(NEW, hash.getValue())).isTrue();
        verify(attempts).record(LoginAttempts.Method.PASSWORD_CHANGE, identifier, ID, ADDRESS,
                LoginAttempts.Outcome.SUCCESS, NOW);
    }

    @Test
    void withoutTheSessionCookieEverySessionEnds() {
        givenAccount(teacher(encoder.encode(CURRENT), false, true));
        when(sessions.findByPrincipalName(ID.toString())).thenReturn(Map.of("phone", new MapSession("phone")));

        assertThat(service.change(ID, CURRENT, NEW, ADDRESS, null)).isEqualTo(1);
        verify(sessions).deleteById("phone");
    }

    @Test
    void aWrongCurrentPasswordIsRecordedForTheLockoutAndChangesNothing() {
        givenAccount(teacher(encoder.encode(CURRENT), false, true));

        assertRefused(() -> service.change(ID, "Not-My-Password-1", NEW, ADDRESS, null),
                Problem.CURRENT_PASSWORD_WRONG);
        verify(attempts).record(LoginAttempts.Method.PASSWORD_CHANGE, identifier, ID, ADDRESS,
                LoginAttempts.Outcome.BAD_CREDENTIALS, NOW);
        verify(accounts, never()).setPassword(any(), any(), any(), any());
        verify(sessions, never()).deleteById(anyString());
    }

    @Test
    void aCurrentPasswordOver72BytesNeverMatches() {
        givenAccount(teacher(encoder.encode(CURRENT), false, true));
        assertRefused(() -> service.change(ID, "ї".repeat(40), NEW, ADDRESS, null), Problem.CURRENT_PASSWORD_WRONG);
    }

    @Test
    void theNewPasswordFollowsThePolicy() {
        givenAccount(teacher(encoder.encode(CURRENT), false, true));

        OwnPasswordRefusal tooShort = refusal(() -> service.change(ID, CURRENT, "short-1", ADDRESS, null));
        assertThat(tooShort.problem()).isEqualTo(Problem.PASSWORD_REJECTED);
        assertThat(tooShort.reason()).isEqualTo("too-short");
        assertThat(tooShort.getMessage()).contains("щонайменше " + properties.password().minLength());

        assertThat(refusal(() -> service.change(ID, CURRENT, "Krentsin@VTC.vn.ua", ADDRESS, null)).reason())
                .isEqualTo("same-as-email");
        assertThat(refusal(() -> service.change(ID, CURRENT, "a".repeat(20), ADDRESS, null)).reason())
                .isEqualTo("too-common");
        OwnPasswordRefusal same = refusal(() -> service.change(ID, CURRENT, CURRENT, ADDRESS, null));
        assertThat(same.reason()).isEqualTo("same-as-current");
        assertThat(same.getMessage()).isEqualTo("Новий пароль збігається з поточним.");
        verify(accounts, never()).setPassword(any(), any(), any(), any());
    }

    @Test
    void aLockedIdentifierIsRefusedWithoutCheckingThePassword() {
        PasswordEncoder counting = mock(PasswordEncoder.class);
        service = new OwnPasswordService(accounts, new PasswordPolicy(properties), counting, attempts, properties,
                sessions, Clock.fixed(NOW, ZoneOffset.UTC));
        givenAccount(teacher(encoder.encode(CURRENT), false, true));
        int max = properties.lockout().maxFailures();
        when(attempts.stats(any(), any(), isNull(), eq(NOW), any()))
                .thenReturn(new LockoutPolicy.Stats(max, NOW.minusSeconds(5), max, 0, false));

        assertRefused(() -> service.change(ID, CURRENT, NEW, ADDRESS, null), Problem.TOO_MANY_ATTEMPTS);
        verify(counting, never()).matches(any(), any());
        verify(attempts).record(LoginAttempts.Method.PASSWORD_CHANGE, identifier, ID, ADDRESS,
                LoginAttempts.Outcome.LOCKED, NOW);
    }

    @Test
    void theGlobalGateRefusesWhenAnotherCheckJustPassed() {
        givenAccount(teacher(encoder.encode(CURRENT), false, true));
        int slowdown = properties.lockout().slowdownFailures();
        when(attempts.stats(any(), any(), isNull(), eq(NOW), any()))
                .thenReturn(new LockoutPolicy.Stats(0, null, slowdown, 0, false));
        when(attempts.passGate(eq(identifier), eq(NOW), any())).thenReturn(false);

        assertRefused(() -> service.change(ID, CURRENT, NEW, ADDRESS, null), Problem.TOO_MANY_ATTEMPTS);
        verify(attempts).record(LoginAttempts.Method.PASSWORD_CHANGE, identifier, ID, ADDRESS,
                LoginAttempts.Outcome.THROTTLED, NOW);
    }

    @Test
    void otherPeoplesFailuresOnTheSameNetworkDoNotBlockTheChange() {
        givenAccount(teacher(encoder.encode(CURRENT), false, true));
        when(attempts.stats(any(), any(), isNull(), eq(NOW), any()))
                .thenReturn(new LockoutPolicy.Stats(0, null, 0, 500, false));
        when(sessions.findByPrincipalName(ID.toString())).thenReturn(Map.of());

        assertThat(service.change(ID, CURRENT, NEW, ADDRESS, null)).isZero();
    }

    @Test
    void googleOnlyDemoAndDisabledAccountsAreRefused() {
        givenAccount(teacher(null, false, true));
        assertRefused(() -> service.change(ID, CURRENT, NEW, ADDRESS, null), Problem.PASSWORD_NOT_SET);

        givenAccount(teacher(encoder.encode(CURRENT), true, true));
        assertRefused(() -> service.change(ID, CURRENT, NEW, ADDRESS, null), Problem.DEMO_ACCOUNT);

        givenAccount(teacher(encoder.encode(CURRENT), false, false));
        assertRefused(() -> service.change(ID, CURRENT, NEW, ADDRESS, null), Problem.ACCOUNT_INACTIVE);
        assertRefused(() -> service.info(ID), Problem.ACCOUNT_INACTIVE);

        when(accounts.findById(ID)).thenReturn(Optional.empty());
        assertRefused(() -> service.info(ID), Problem.ACCOUNT_INACTIVE);
        verify(attempts, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingOrAbsurdlyLongFieldsAreAValidationError() {
        assertRefused(() -> service.change(ID, null, NEW, ADDRESS, null), Problem.VALIDATION);
        assertRefused(() -> service.change(ID, CURRENT, "", ADDRESS, null), Problem.VALIDATION);
        assertRefused(() -> service.change(ID, "x".repeat(OwnPasswordService.MAX_INPUT_LENGTH + 1), NEW, ADDRESS,
                null), Problem.VALIDATION);
        verify(accounts, never()).findById(any());
    }

    @Test
    void aGoogleOnlyAccountNeedsAFreshGoogleSignInOfItsOwnBeforeAFirstPassword() {
        givenAccount(teacher(null, false, true));

        assertThat(service.info(ID).reauthRequired()).isTrue();
        assertThat(service.info(ID, mark(ID, ID, NOW.minusSeconds(60))).reauthRequired()).isFalse();
        assertThat(service.info(ID, mark(ID, ID, NOW.minus(OwnPasswordService.REAUTH_WINDOW).minusSeconds(1)))
                .reauthRequired()).as("stale").isTrue();
        assertThat(service.info(ID, mark(OTHER, OTHER, NOW)).reauthRequired()).as("another account").isTrue();
        assertThat(service.info(ID, mark(OTHER, ID, NOW)).reauthRequired())
                .as("session signed in as another account since").isTrue();
        assertThat(service.info(ID, mark(ID, ID, NOW.plusSeconds(60))).reauthRequired())
                .as("from the future").isTrue();

        givenAccount(teacher(encoder.encode(CURRENT), false, true));
        assertThat(service.info(ID, Optional.empty()).reauthRequired()).as("has a password").isFalse();
    }

    @Test
    void aFreshGoogleSignInSetsTheFirstPasswordAndEndsTheOtherSessions() {
        givenAccount(teacher(null, false, true));
        when(accounts.setInitialPassword(eq(ID), anyString(), eq(NOW), eq("krentsin"))).thenReturn(true);
        Map<String, Session> open = new LinkedHashMap<>();
        open.put("this-browser", new MapSession("this-browser"));
        open.put("phone", new MapSession("phone"));
        when(sessions.findByPrincipalName(ID.toString())).thenReturn(open);

        int ended = service.setInitial(ID, NEW, ADDRESS, mark(ID, ID, NOW.minusSeconds(30)), "this-browser");

        assertThat(ended).isEqualTo(1);
        verify(sessions).deleteById("phone");
        verify(sessions, never()).deleteById("this-browser");
        var hash = ArgumentCaptor.forClass(String.class);
        verify(accounts).setInitialPassword(eq(ID), hash.capture(), eq(NOW), eq("krentsin"));
        assertThat(encoder.matches(NEW, hash.getValue())).isTrue();
        verify(accounts, never()).setPassword(any(), any(), any(), any());
        verify(attempts).record(LoginAttempts.Method.PASSWORD_SET, identifier, ID, ADDRESS,
                LoginAttempts.Outcome.SUCCESS, NOW);
    }

    @Test
    void withoutAFreshGoogleSignInOfThisAccountNoPasswordIsSetAndTheAttemptCounts() {
        givenAccount(teacher(null, false, true));

        assertRefused(() -> service.setInitial(ID, NEW, ADDRESS, Optional.empty(), null), Problem.REAUTH_REQUIRED);
        assertRefused(() -> service.setInitial(ID, NEW, ADDRESS, mark(ID, ID, NOW.minusSeconds(11 * 60)), "s"),
                Problem.REAUTH_REQUIRED);
        assertRefused(() -> service.setInitial(ID, NEW, ADDRESS, mark(OTHER, OTHER, NOW), "s"),
                Problem.REAUTH_REQUIRED);

        verify(attempts, times(3)).record(LoginAttempts.Method.PASSWORD_SET, identifier, ID, ADDRESS,
                LoginAttempts.Outcome.BAD_CREDENTIALS, NOW);
        verify(accounts, never()).setInitialPassword(any(), any(), any(), any());
        verify(sessions, never()).deleteById(anyString());
    }

    @Test
    void aFirstPasswordFollowsThePolicyAndTheLockout() {
        givenAccount(teacher(null, false, true));
        OwnPasswordRefusal tooShort = refusal(() -> service.setInitial(ID, "short-1", ADDRESS,
                mark(ID, ID, NOW), null));
        assertThat(tooShort.problem()).isEqualTo(Problem.PASSWORD_REJECTED);
        assertThat(tooShort.reason()).isEqualTo("too-short");
        assertThat(refusal(() -> service.setInitial(ID, "a".repeat(20), ADDRESS, mark(ID, ID, NOW), null)).reason())
                .isEqualTo("too-common");

        int max = properties.lockout().maxFailures();
        when(attempts.stats(any(), any(), isNull(), eq(NOW), any()))
                .thenReturn(new LockoutPolicy.Stats(max, NOW.minusSeconds(5), max, 0, false));
        assertRefused(() -> service.setInitial(ID, NEW, ADDRESS, mark(ID, ID, NOW), null), Problem.TOO_MANY_ATTEMPTS);
        verify(attempts).record(LoginAttempts.Method.PASSWORD_SET, identifier, ID, ADDRESS,
                LoginAttempts.Outcome.LOCKED, NOW);
        verify(accounts, never()).setInitialPassword(any(), any(), any(), any());
    }

    @Test
    void aFirstPasswordIsRefusedWhenThereIsOneDemoDisabledOrTheRaceWasLost() {
        givenAccount(teacher(encoder.encode(CURRENT), false, true));
        assertRefused(() -> service.setInitial(ID, NEW, ADDRESS, mark(ID, ID, NOW), null),
                Problem.PASSWORD_ALREADY_SET);
        givenAccount(teacher(encoder.encode(CURRENT), true, true));
        assertRefused(() -> service.setInitial(ID, NEW, ADDRESS, mark(ID, ID, NOW), null), Problem.DEMO_ACCOUNT);
        givenAccount(teacher(null, false, false));
        assertRefused(() -> service.setInitial(ID, NEW, ADDRESS, mark(ID, ID, NOW), null), Problem.ACCOUNT_INACTIVE);
        assertRefused(() -> service.setInitial(ID, "", ADDRESS, mark(ID, ID, NOW), null), Problem.VALIDATION);
        assertRefused(() -> service.setInitial(ID, "x".repeat(OwnPasswordService.MAX_INPUT_LENGTH + 1), ADDRESS,
                mark(ID, ID, NOW), null), Problem.VALIDATION);

        givenAccount(teacher(null, false, true));
        when(accounts.setInitialPassword(any(), any(), any(), any())).thenReturn(false);
        assertRefused(() -> service.setInitial(ID, NEW, ADDRESS, mark(ID, ID, NOW), null),
                Problem.PASSWORD_ALREADY_SET);
        verify(sessions, never()).deleteById(anyString());
    }

    private static Optional<GoogleSignInMark> mark(UUID sessionAccount, UUID googleAccount, Instant at) {
        return Optional.of(new GoogleSignInMark(sessionAccount.toString(), googleAccount.toString(), at));
    }

    private void givenAccount(Account account) {
        when(accounts.findById(ID)).thenReturn(Optional.of(account));
    }

    private static Account teacher(String hash, boolean demo, boolean enabled) {
        return new Account(ID, "krentsin", "krentsin@vtc.vn.ua", "Кренцін М. Д.", hash, enabled,
                enabled ? null : Account.REVOKED, demo, Set.of("TEACHER"), null);
    }

    private static void assertRefused(Runnable call, Problem problem) {
        assertThat(refusal(call).problem()).isEqualTo(problem);
    }

    private static OwnPasswordRefusal refusal(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(OwnPasswordRefusal.class);
        return (OwnPasswordRefusal) thrown;
    }
}
