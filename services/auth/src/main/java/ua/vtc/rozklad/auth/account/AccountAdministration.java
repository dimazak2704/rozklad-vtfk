package ua.vtc.rozklad.auth.account;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import ua.vtc.rozklad.auth.account.AccountRuleException.Rule;

/**
 * Account changes requested by core over the internal API (DESIGN §1.3). Core owns the business rules of
 * access grants; this class keeps the invariants that must hold whatever the caller does:
 * <ul>
 *   <li>at least one usable admin (D12): every change that can remove one takes the advisory lock first;</li>
 *   <li>revocation is sticky (D11): only {@code enable(clearRevocation=true)} clears {@code REVOKED};</li>
 *   <li>passwords pass {@link PasswordPolicy}; identifiers are unique and lower case.</li>
 * </ul>
 * Revoke and delete also end the account's sign-in sessions and drop its OAuth2 authorizations, so the next
 * silent renew fails; access tokens already issued live until {@code exp}.
 */
@Service
public class AccountAdministration {

    static final Pattern USERNAME = Pattern.compile("^[a-z][a-z0-9._-]{2,49}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+$");
    private static final int MAX_EMAIL = 254;
    private static final int MAX_DISPLAY_NAME = 200;
    private static final int MAX_ACTOR = 100;
    private static final Logger log = LoggerFactory.getLogger(AccountAdministration.class);

    private final AccountRepository accounts;
    private final PasswordPolicy policy;
    private final PasswordEncoder passwordEncoder;
    private final JdbcClient jdbc;
    private final Clock clock;

    public AccountAdministration(AccountRepository accounts, PasswordPolicy policy, PasswordEncoder passwordEncoder,
            JdbcClient jdbc, Clock clock) {
        this.accounts = accounts;
        this.policy = policy;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Idempotent create with an id chosen by core: an existing id is left unchanged ({@code false}), so a retry
     * after a timeout never creates a second account.
     */
    @Transactional
    public boolean create(UUID id, NewAccount request, String actor) {
        if (accounts.findById(id).isPresent()) {
            return false;
        }
        String username = username(request.username());
        String email = email(request.email());
        if (username == null && email == null) {
            throw validation("username or email is required");
        }
        String displayName = displayName(request.displayName());
        if (displayName == null) {
            throw validation("displayName is required");
        }
        Set<String> roles = roles(request.roles());
        checkUnique(id, username, email);
        String hash = null;
        if (request.password() != null) {
            checkPassword(request.password(), username, email);
            hash = passwordEncoder.encode(request.password());
        }
        Instant now = clock.instant();
        try {
            accounts.insert(new AccountRepository.NewAccount(id, username, email, displayName, roles, hash, false), now,
                    actor(actor));
        } catch (DataIntegrityViolationException race) {
            throw uniqueViolation(race);
        }
        if (!request.enabled()) {
            accounts.disable(id, null, now, actor(actor));
        }
        log.info("account {} created by {} with roles {}", id, actor(actor), roles);
        return true;
    }

    /** Profile and roles; {@code null} keeps a field. Removing ADMIN from the last usable admin is refused. */
    @Transactional
    public void update(UUID id, AccountPatch patch, String actor) {
        Account account = require(id);
        String username = patch.username() == null ? null : username(patch.username());
        String email = patch.email() == null ? null : email(patch.email());
        if (patch.username() != null && username == null || patch.email() != null && email == null) {
            throw validation("username and email cannot be cleared");
        }
        String displayName = patch.displayName() == null ? null : displayName(patch.displayName());
        if (patch.displayName() != null && displayName == null) {
            throw validation("displayName cannot be blank");
        }
        checkUnique(id, username, email);
        if (patch.roles() != null) {
            Set<String> roles = roles(patch.roles());
            if (account.roles().contains(Roles.ADMIN) && !roles.contains(Roles.ADMIN)) {
                requireAnotherUsableAdmin(id);
            }
            accounts.replaceRoles(id, roles);
        }
        try {
            accounts.updateProfile(id, username, email, displayName, clock.instant(), actor(actor));
        } catch (DataIntegrityViolationException race) {
            throw uniqueViolation(race);
        }
    }

    /** Sets a new password; never changes {@code enabled}. */
    @Transactional
    public void setPassword(UUID id, String password, String actor) {
        Account account = require(id);
        checkPassword(password, account.username(), account.email());
        accounts.setPassword(id, passwordEncoder.encode(password), clock.instant(), actor(actor));
    }

    /** Disables the account as {@code REVOKED} and ends its sessions and authorizations. */
    @Transactional
    public void revoke(UUID id, String actor) {
        require(id);
        requireAnotherUsableAdmin(id);
        accounts.disable(id, Account.REVOKED, clock.instant(), actor(actor));
        int[] ended = endSignIns(id);
        log.info("account {} revoked by {}: {} sessions and {} authorizations removed", id, actor(actor), ended[0],
                ended[1]);
    }

    /** Enables the account; a revoked one only with {@code clearRevocation} (D11). */
    @Transactional
    public void enable(UUID id, boolean clearRevocation, String actor) {
        Account account = require(id);
        if (account.revoked() && !clearRevocation) {
            throw new AccountRuleException(Rule.ACCOUNT_REVOKED, "The account is revoked; only a restore enables it");
        }
        if (!account.enabled() || account.disabledReason() != null) {
            accounts.enableClearingReason(id, clock.instant(), actor(actor));
            log.info("account {} enabled by {}{}", id, actor(actor), account.revoked() ? " (revocation cleared)" : "");
        }
    }

    /** Idempotent. Refused when the account is the last usable admin and Google is its only credential. */
    @Transactional
    public void unbindGoogle(UUID id, String actor) {
        Account account = require(id);
        if (!account.hasPassword()) {
            requireAnotherUsableAdmin(id);
        }
        if (accounts.unbindGoogle(id)) {
            log.info("Google binding of account {} removed by {}", id, actor(actor));
        }
    }

    /** Idempotent (an unknown id is fine); used only to compensate a failed invite in core. */
    @Transactional
    public void delete(UUID id, String actor) {
        if (accounts.findById(id).isEmpty()) {
            return;
        }
        requireAnotherUsableAdmin(id);
        endSignIns(id);
        accounts.delete(id);
        log.info("account {} deleted by {}", id, actor(actor));
    }

    /**
     * Refuses a change that would leave no usable admin when the target is one. The advisory lock serialises
     * such changes, so two admins revoking each other cannot both succeed.
     */
    private void requireAnotherUsableAdmin(UUID id) {
        accounts.lockAdminInvariant();
        Account account = require(id);
        boolean usableAdmin = account.enabled() && account.roles().contains(Roles.ADMIN)
                && (account.hasPassword() || accounts.hasGoogleBinding(id));
        if (usableAdmin && accounts.countUsableAdminsExcept(id) == 0) {
            throw new AccountRuleException(Rule.LAST_ADMIN, "At least one usable admin must remain");
        }
    }

    /** Deletes Spring Sessions and OAuth2 authorizations of the account (principal name = account id, D9). */
    private int[] endSignIns(UUID id) {
        int sessions = jdbc.sql("DELETE FROM spring_session WHERE principal_name = ?").param(id.toString()).update();
        int authorizations = jdbc.sql("DELETE FROM oauth2_authorization WHERE principal_name = ?")
                .param(id.toString()).update();
        return new int[] {sessions, authorizations};
    }

    private Account require(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new AccountRuleException(Rule.NOT_FOUND, "Account not found"));
    }

    private void checkUnique(UUID id, String username, String email) {
        if (username != null && accounts.usernameTaken(username, id)) {
            throw new AccountRuleException(Rule.USERNAME_TAKEN, "Username is taken");
        }
        if (email != null && accounts.emailTaken(email, id)) {
            throw new AccountRuleException(Rule.EMAIL_TAKEN, "E-mail is taken");
        }
    }

    private void checkPassword(String password, String username, String email) {
        policy.check(password, username, email).ifPresent(reason -> {
            throw AccountRuleException.passwordRejected(reason);
        });
    }

    private static AccountRuleException uniqueViolation(DataIntegrityViolationException race) {
        String message = String.valueOf(race.getMostSpecificCause().getMessage());
        if (message.contains("uq_account_username")) {
            return new AccountRuleException(Rule.USERNAME_TAKEN, "Username is taken");
        }
        if (message.contains("uq_account_email")) {
            return new AccountRuleException(Rule.EMAIL_TAKEN, "E-mail is taken");
        }
        throw race;
    }

    /** Lower case and the shared pattern; blank means "none". */
    private static String username(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String username = value.strip().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(username).matches()) {
            throw validation("username must match " + USERNAME.pattern());
        }
        return username;
    }

    private static String email(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String email = value.strip().toLowerCase(Locale.ROOT);
        if (email.length() > MAX_EMAIL || !EMAIL.matcher(email).matches()) {
            throw validation("email is not valid");
        }
        return email;
    }

    private static String displayName(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String name = value.strip();
        if (name.length() > MAX_DISPLAY_NAME) {
            throw validation("displayName is longer than " + MAX_DISPLAY_NAME);
        }
        return name;
    }

    private static Set<String> roles(Set<String> roles) {
        if (roles == null || roles.isEmpty() || !Roles.HUMAN.containsAll(roles)) {
            throw validation("roles must be a non-empty subset of " + Roles.HUMAN);
        }
        return Set.copyOf(roles);
    }

    /** {@code X-Actor} of core (the admin's {@code preferred_username}); trusted because only core calls. */
    static String actor(String actor) {
        if (!StringUtils.hasText(actor)) {
            return "rozklad-core";
        }
        String value = actor.strip();
        return value.length() > MAX_ACTOR ? value.substring(0, MAX_ACTOR) : value;
    }

    private static AccountRuleException validation(String message) {
        return new AccountRuleException(Rule.VALIDATION, message);
    }

    /** {@code PUT /internal/accounts/{id}}; {@code password} may be {@code null} (no password login). */
    public record NewAccount(String username, String email, String displayName, Set<String> roles, String password,
            boolean enabled) {

        @Override
        public String toString() {
            return "NewAccount[username=" + username + ", email=" + email + ", roles=" + roles + ", password=***]";
        }
    }

    /** {@code PATCH /internal/accounts/{id}}; {@code null} keeps a field. */
    public record AccountPatch(String username, String email, String displayName, Set<String> roles) {
    }
}
