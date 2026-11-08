package ua.vtc.rozklad.core.identity.internal;

import java.util.Optional;
import java.util.UUID;

/**
 * User accounts in the identity provider (Keycloak realm {@code rozklad}). Behind an interface so
 * tests use an in-memory fake instead of a running Keycloak.
 */
public interface IdentityProvider {

    /**
     * Creates an enabled user with the password (passwords are hashed by the provider).
     *
     * @throws UsernameTakenException     when the username exists
     * @throws PasswordRejectedException  when the realm password policy rejects the password
     * @throws IdentityProviderException  when the provider is unreachable or fails
     */
    UUID createUser(NewAccount account);

    Optional<Account> findUser(UUID userId);

    /** Exact (case-insensitive) username match; Keycloak stores usernames in lower case. */
    Optional<Account> findUserByUsername(String username);

    /** Enables the user and sets a new password (re-invite of an already linked teacher). */
    void restoreAccess(UUID userId, String password);

    /** Idempotent: a role the user already has is not an error. */
    void grantRealmRole(UUID userId, String role);

    /** Removes the user; a user that does not exist is not an error. */
    void deleteUser(UUID userId);

    record NewAccount(String username, String password, String firstName, String lastName) {

        @Override
        public String toString() {
            return "NewAccount[username=" + username + ", password=***]";
        }
    }

    record Account(UUID id, String username, boolean enabled) {
    }

    class UsernameTakenException extends RuntimeException {

        public UsernameTakenException(String username) {
            super("Username is taken: " + username);
        }
    }

    class PasswordRejectedException extends RuntimeException {

        public PasswordRejectedException(String reason) {
            super(reason);
        }
    }

    class IdentityProviderException extends RuntimeException {

        public IdentityProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
