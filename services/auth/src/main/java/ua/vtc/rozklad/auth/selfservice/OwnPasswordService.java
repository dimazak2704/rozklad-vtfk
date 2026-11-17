package ua.vtc.rozklad.auth.selfservice;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import ua.vtc.rozklad.auth.account.Account;
import ua.vtc.rozklad.auth.account.AccountRepository;
import ua.vtc.rozklad.auth.account.PasswordPolicy;
import ua.vtc.rozklad.auth.config.AuthServerProperties;
import ua.vtc.rozklad.auth.login.ClientAddress;
import ua.vtc.rozklad.auth.login.LockoutPolicy;
import ua.vtc.rozklad.auth.login.LoginAttempts;
import ua.vtc.rozklad.auth.selfservice.OwnPasswordRefusal.Problem;

/**
 * A signed-in person changes their own password (CONTRACTS §0.1). The current password is checked like a sign-in:
 * the attempt goes to {@code login_attempt} (method {@code PASSWORD_CHANGE}) under the account's sign-in identifier
 * and the client's network, so the per-identifier lockout and the global gate of {@link LockoutPolicy} cover both
 * doors. A stolen access token therefore cannot guess the password faster than the sign-in page allows. The
 * network throttle is not applied: the token already names one account, and other people's failures on the
 * college NAT must not block a teacher's change.
 * <p>
 * The new password passes {@link PasswordPolicy} and must differ from the current one. Afterwards every other
 * sign-in session of the account ends (the one the request came from, if its cookie came along, stays), so other
 * devices cannot renew their tokens; access tokens already issued live until {@code exp}. OAuth2 authorizations
 * are kept: the SPA has no refresh token, and the other devices' ID tokens still work for logout.
 * <p>
 * Accounts without a password (Google sign-in only) cannot set one here: that would let a stolen token add a
 * credential. They get a password link from «Доступи». Shared demo accounts of the server keep their password.
 */
@Service
public class OwnPasswordService {

    private static final Logger log = LoggerFactory.getLogger(OwnPasswordService.class);
    /** A request field longer than this is not a password anybody typed. */
    static final int MAX_INPUT_LENGTH = 1024;

    private final AccountRepository accounts;
    private final PasswordPolicy policy;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttempts attempts;
    private final AuthServerProperties.Lockout lockout;
    private final LockoutPolicy lockoutPolicy;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final Clock clock;

    public OwnPasswordService(AccountRepository accounts, PasswordPolicy policy, PasswordEncoder passwordEncoder,
            LoginAttempts attempts, AuthServerProperties properties,
            FindByIndexNameSessionRepository<? extends Session> sessions, Clock clock) {
        this.accounts = accounts;
        this.policy = policy;
        this.passwordEncoder = passwordEncoder;
        this.attempts = attempts;
        this.lockout = properties.lockout();
        this.lockoutPolicy = new LockoutPolicy(lockout);
        this.sessions = sessions;
        this.clock = clock;
    }

    /** Whether the person can change the password here, and the length limits of the policy. */
    public Info info(UUID accountId) {
        return new Info(status(requireActive(accountId)), policy.minLength(), PasswordPolicy.MAX_LENGTH);
    }

    /**
     * Checks the current password, stores the new one and ends the account's other sign-in sessions.
     *
     * @param keepSessionId the sign-in session of this request ({@code null}: none came, every session ends)
     * @return how many other sessions ended
     * @throws OwnPasswordRefusal when the change is refused
     */
    public int change(UUID accountId, String currentPassword, String newPassword, ClientAddress address,
            String keepSessionId) {
        if (isMissing(currentPassword) || isMissing(newPassword)
                || currentPassword.length() > MAX_INPUT_LENGTH || newPassword.length() > MAX_INPUT_LENGTH) {
            throw new OwnPasswordRefusal(Problem.VALIDATION);
        }
        Account account = requireActive(accountId);
        switch (status(account)) {
            case DEMO -> throw new OwnPasswordRefusal(Problem.DEMO_ACCOUNT);
            case NO_PASSWORD -> throw new OwnPasswordRefusal(Problem.PASSWORD_NOT_SET);
            case CHANGEABLE -> { }
        }
        byte[] identifier = LoginAttempts.hash(account.loginName());
        Instant now = clock.instant();
        LockoutPolicy.Decision decision = decide(identifier, address, now);
        if (decision == LockoutPolicy.Decision.LOCKED || decision == LockoutPolicy.Decision.THROTTLED) {
            attempts.record(LoginAttempts.Method.PASSWORD_CHANGE, identifier, accountId, address,
                    decision == LockoutPolicy.Decision.LOCKED ? LoginAttempts.Outcome.LOCKED
                            : LoginAttempts.Outcome.THROTTLED, now);
            log.info("own password change of account {} refused: {}", accountId, decision);
            throw new OwnPasswordRefusal(Problem.TOO_MANY_ATTEMPTS);
        }
        if (!currentMatches(currentPassword, account.passwordHash())) {
            attempts.record(LoginAttempts.Method.PASSWORD_CHANGE, identifier, accountId, address,
                    LoginAttempts.Outcome.BAD_CREDENTIALS, now);
            log.info("own password change of account {} refused: wrong current password", accountId);
            throw new OwnPasswordRefusal(Problem.CURRENT_PASSWORD_WRONG);
        }
        attempts.record(LoginAttempts.Method.PASSWORD_CHANGE, identifier, accountId, address,
                LoginAttempts.Outcome.SUCCESS, now);
        policy.check(newPassword, account.username(), account.email()).ifPresent(reason -> {
            throw new OwnPasswordRefusal(Problem.PASSWORD_REJECTED, rejectionText(reason), reason.code());
        });
        if (newPassword.equals(currentPassword)) {
            throw new OwnPasswordRefusal(Problem.PASSWORD_REJECTED, "Новий пароль збігається з поточним.",
                    "same-as-current");
        }
        accounts.setPassword(accountId, passwordEncoder.encode(newPassword), now, account.loginName());
        int ended = endOtherSessions(accountId, keepSessionId);
        log.info("own password of account {} changed: {} other sessions ended", accountId, ended);
        return ended;
    }

    private LockoutPolicy.Decision decide(byte[] identifier, ClientAddress address, Instant now) {
        LockoutPolicy.Stats stats = attempts.stats(identifier, address.prefix(), null, now, lockout);
        // без мережевого обмеження: токен уже називає один обліковий запис (див. опис класу)
        LockoutPolicy.Stats own = new LockoutPolicy.Stats(stats.keyFailures(), stats.lastFailure(),
                stats.identifierFailures(), 0, false);
        LockoutPolicy.Decision decision = lockoutPolicy.decide(own, now);
        if (decision == LockoutPolicy.Decision.GATED
                && !attempts.passGate(identifier, now, lockoutPolicy.gateDelay(own))) {
            return LockoutPolicy.Decision.THROTTLED;
        }
        return decision;
    }

    /** A current password over 72 bytes cannot match: the policy never stored one, and BCrypt would refuse it. */
    private boolean currentMatches(String currentPassword, String hash) {
        if (currentPassword.getBytes(StandardCharsets.UTF_8).length > PasswordPolicy.MAX_BYTES) {
            return false;
        }
        return passwordEncoder.matches(currentPassword, hash);
    }

    private int endOtherSessions(UUID accountId, String keepSessionId) {
        int ended = 0;
        for (String id : sessions.findByPrincipalName(accountId.toString()).keySet()) {
            if (!Objects.equals(id, keepSessionId)) {
                sessions.deleteById(id);
                ended++;
            }
        }
        return ended;
    }

    private Account requireActive(UUID accountId) {
        return accounts.findById(accountId).filter(Account::enabled)
                .orElseThrow(() -> new OwnPasswordRefusal(Problem.ACCOUNT_INACTIVE));
    }

    private static Status status(Account account) {
        if (account.demo()) {
            return Status.DEMO;
        }
        return account.hasPassword() ? Status.CHANGEABLE : Status.NO_PASSWORD;
    }

    private String rejectionText(PasswordPolicy.Reason reason) {
        return switch (reason) {
            case TOO_SHORT -> "Новий пароль має містити щонайменше " + policy.minLength() + " символів.";
            case TOO_LONG -> "Новий пароль задовгий: не більше " + PasswordPolicy.MAX_LENGTH + " символів.";
            case SAME_AS_USERNAME -> "Пароль не може збігатися з логіном.";
            case SAME_AS_EMAIL -> "Пароль не може збігатися з адресою e-mail.";
            case TOO_COMMON -> "Цей пароль надто поширений. Придумайте інший.";
        };
    }

    private static boolean isMissing(String value) {
        return value == null || value.isEmpty();
    }

    /** {@code CHANGEABLE}: has a password; {@code NO_PASSWORD}: Google sign-in only; {@code DEMO}: shared demo. */
    public enum Status { CHANGEABLE, NO_PASSWORD, DEMO }

    public record Info(Status status, int minLength, int maxLength) {
    }
}
