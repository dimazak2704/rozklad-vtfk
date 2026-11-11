package ua.vtc.rozklad.core.identity.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import ua.vtc.rozklad.core.shared.security.Roles;

/**
 * User accounts in the sign-in service {@code auth} (internal accounts API, DESIGN §1.3). Behind an interface
 * so tests use an in-memory fake instead of a running {@code auth}. Every method throws
 * {@link IdentityProviderException} when {@code auth} is unreachable, refuses the service token or fails.
 */
public interface IdentityProvider {

    /**
     * Creates the account with the id core chose ({@link NewAccount#id()}). Idempotent: a retry with the same id
     * does not create a second account.
     *
     * @throws UsernameTakenException    when the username exists
     * @throws EmailTakenException       when the e-mail exists
     * @throws PasswordRejectedException when the password policy of {@code auth} rejects the password
     */
    UUID createUser(NewAccount account);

    Optional<Account> findUser(UUID userId);

    /** Exact (case-insensitive) username match; usernames are stored in lower case. */
    Optional<Account> findUserByUsername(String username);

    /** Exact (case-insensitive) e-mail match. */
    Optional<Account> findUserByEmail(String email);

    /** The accounts that exist among {@code ids}; unknown ids are skipped. */
    List<Account> findUsers(Collection<UUID> ids);

    /**
     * Sets a new password and enables the account (re-invite). Never clears a revocation.
     *
     * @throws AccountRevokedException when the account is revoked; the password is set, the account stays off
     */
    void restoreAccess(UUID userId, String password);

    /** Sets a new password; never changes whether the account is enabled. */
    void setPassword(UUID userId, String password);

    /** Gives an account without a username one (an access grant that signs in by password the first time). */
    void setUsername(UUID userId, String username);

    /** Idempotent: a role the account already has is not an error. */
    void grantRole(UUID userId, String role);

    /** @throws LastAdminException when this removes ADMIN from the last usable admin */
    void setRoles(UUID userId, Set<String> roles);

    /** Disables the account as revoked and ends its sign-in sessions. @throws LastAdminException */
    void revoke(UUID userId);

    /** The only way to clear a revocation. */
    void restore(UUID userId);

    /** Removes the Google binding (idempotent). @throws LastAdminException */
    void unbindGoogle(UUID userId);

    /** Removes the account; an account that does not exist is not an error. */
    void deleteUser(UUID userId);

    /**
     * A new account; {@code id} is chosen by core so that a retried create stays one account. {@code username}
     * or {@code email} must be set; {@code password} may be {@code null} (Google or a later password link).
     */
    record NewAccount(UUID id, String username, String email, String displayName, Set<String> roles,
                      String password) {

        public NewAccount {
            roles = Set.copyOf(roles);
        }

        /** An account for a new teacher who accepted an invite. */
        public static NewAccount teacher(String username, String displayName, String password) {
            return new NewAccount(UUID.randomUUID(), username, null, displayName,
                    Set.of(Roles.TEACHER), password);
        }

        @Override
        public String toString() {
            return "NewAccount[id=" + id + ", username=" + username + ", email=" + email + ", roles=" + roles
                    + ", password=***]";
        }
    }

    /**
     * An account as {@code auth} reports it. {@code username} is {@code null} for an access grant by e-mail.
     *
     * @param disabledReason {@code REVOKED} (only {@link #restore} clears it), {@code DEMO_OFF} or {@code null}
     */
    record Account(UUID id, String username, String email, String displayName, Set<String> roles, boolean enabled,
                   String disabledReason, boolean hasPassword, boolean googleLinked, boolean demo,
                   Instant lastLoginAt) {

        public static final String REVOKED = "REVOKED";

        public Account {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
        }

        public boolean revoked() {
            return REVOKED.equals(disabledReason);
        }

        /** The username, else the e-mail: what the admin sees and types. */
        public String loginName() {
            return username != null ? username : email;
        }
    }

    class UsernameTakenException extends RuntimeException {

        public UsernameTakenException(String username) {
            super("Username is taken: " + username);
        }
    }

    class EmailTakenException extends RuntimeException {

        public EmailTakenException(String email) {
            super("E-mail is taken: " + email);
        }
    }

    /**
     * The password policy of {@code auth} refused the password. {@code reason} is one of {@code too-short},
     * {@code too-long}, {@code same-as-username}, {@code same-as-email}, {@code too-common} (or unknown).
     */
    class PasswordRejectedException extends RuntimeException {

        private final String reason;

        public PasswordRejectedException(String reason) {
            super("Password rejected: " + reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /** The change would leave no admin who can sign in. */
    class LastAdminException extends RuntimeException {

        public LastAdminException() {
            super("At least one usable admin must remain");
        }
    }

    /** The account is revoked; only an explicit restore enables it. */
    class AccountRevokedException extends RuntimeException {

        public AccountRevokedException(UUID userId) {
            super("Account is revoked: " + userId);
        }
    }

    /** A write to an account that does not exist (any more). */
    class AccountNotFoundException extends RuntimeException {

        public AccountNotFoundException(UUID userId) {
            super("Account not found: " + userId);
        }
    }

    class IdentityProviderException extends RuntimeException {

        public IdentityProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
