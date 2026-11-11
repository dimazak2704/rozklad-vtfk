package ua.vtc.rozklad.core.identity.internal;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sign-in service for tests: accounts with password, e-mail, enabled flag, revocation and roles. It
 * keeps the rules of {@code auth} that core relies on: unique usernames and e-mails, and a revoked account is
 * enabled only by {@link #restore}.
 */
public class FakeIdentityProvider implements IdentityProvider {

    private final Map<UUID, User> users = new ConcurrentHashMap<>();
    private volatile boolean unavailable;
    private volatile boolean failAfterCreate;
    private volatile String passwordRejection;

    /** An account that already exists (e.g. a demo account). */
    public UUID existingUser(String username, String password, boolean enabled) {
        UUID id = UUID.randomUUID();
        users.put(id, new User(id, username, null, username, password, enabled, null, new HashSet<>(), false));
        return id;
    }

    /** An access grant by e-mail («Доступи»): no username, no password yet. */
    public UUID grantedByEmail(String email, Set<String> roles) {
        UUID id = UUID.randomUUID();
        users.put(id, new User(id, null, email, email, null, true, null, new HashSet<>(roles), false));
        return id;
    }

    public void goDown() {
        unavailable = true;
    }

    /** The next create stores the account but the answer is lost (a timeout after the write). */
    public void failAfterNextCreate() {
        failAfterCreate = true;
    }

    /** Every password is refused with this {@code reason} of {@code auth}. */
    public void rejectPasswords(String reason) {
        passwordRejection = reason;
    }

    public Optional<User> user(UUID id) {
        return Optional.ofNullable(users.get(id));
    }

    public Optional<User> byUsername(String username) {
        return users.values().stream().filter(user -> username.equals(user.username())).findFirst();
    }

    public int count() {
        return users.size();
    }

    @Override
    public UUID createUser(NewAccount account) {
        ensureUp();
        if (users.containsKey(account.id())) {
            return account.id();
        }
        if (account.username() != null && findUserByUsername(account.username()).isPresent()) {
            throw new UsernameTakenException(account.username());
        }
        if (account.email() != null && findUserByEmail(account.email()).isPresent()) {
            throw new EmailTakenException(account.email());
        }
        if (account.password() != null) {
            checkPassword();
        }
        users.put(account.id(), new User(account.id(), account.username(), account.email(), account.displayName(),
                account.password(), true, null, new HashSet<>(account.roles()), false));
        if (failAfterCreate) {
            failAfterCreate = false;
            throw new IdentityProviderException("auth did not answer", null);
        }
        return account.id();
    }

    @Override
    public Optional<Account> findUser(UUID userId) {
        ensureUp();
        return user(userId).map(User::toAccount);
    }

    @Override
    public Optional<Account> findUserByUsername(String username) {
        ensureUp();
        return users.values().stream()
                .filter(user -> user.username() != null && user.username().equalsIgnoreCase(username))
                .findFirst()
                .map(User::toAccount);
    }

    @Override
    public Optional<Account> findUserByEmail(String email) {
        ensureUp();
        return users.values().stream()
                .filter(user -> user.email() != null && user.email().equalsIgnoreCase(email))
                .findFirst()
                .map(User::toAccount);
    }

    @Override
    public List<Account> findUsers(Collection<UUID> ids) {
        ensureUp();
        return ids.stream().distinct().map(users::get).filter(java.util.Objects::nonNull).map(User::toAccount)
                .toList();
    }

    @Override
    public void restoreAccess(UUID userId, String password) {
        setPassword(userId, password);
        User user = require(userId);
        if (Account.REVOKED.equals(user.disabledReason())) {
            throw new AccountRevokedException(userId);
        }
        users.put(userId, user.withEnabled(true, null));
    }

    @Override
    public void setPassword(UUID userId, String password) {
        ensureUp();
        User user = require(userId);
        checkPassword();
        users.put(userId, new User(userId, user.username(), user.email(), user.displayName(), password,
                user.enabled(), user.disabledReason(), user.roles(), user.googleLinked()));
    }

    @Override
    public void setUsername(UUID userId, String username) {
        ensureUp();
        User user = require(userId);
        findUserByUsername(username).filter(other -> !other.id().equals(userId)).ifPresent(other -> {
            throw new UsernameTakenException(username);
        });
        users.put(userId, new User(userId, username, user.email(), user.displayName(), user.password(),
                user.enabled(), user.disabledReason(), user.roles(), user.googleLinked()));
    }

    @Override
    public void grantRole(UUID userId, String role) {
        ensureUp();
        require(userId).roles().add(role);
    }

    @Override
    public void setRoles(UUID userId, Set<String> roles) {
        ensureUp();
        Set<String> current = require(userId).roles();
        current.clear();
        current.addAll(roles);
    }

    @Override
    public void revoke(UUID userId) {
        ensureUp();
        users.put(userId, require(userId).withEnabled(false, Account.REVOKED));
    }

    @Override
    public void restore(UUID userId) {
        ensureUp();
        users.put(userId, require(userId).withEnabled(true, null));
    }

    @Override
    public void unbindGoogle(UUID userId) {
        ensureUp();
        User user = require(userId);
        users.put(userId, new User(userId, user.username(), user.email(), user.displayName(), user.password(),
                user.enabled(), user.disabledReason(), user.roles(), false));
    }

    @Override
    public void deleteUser(UUID userId) {
        ensureUp();
        users.remove(userId);
    }

    private User require(UUID userId) {
        User user = users.get(userId);
        if (user == null) {
            throw new AccountNotFoundException(userId);
        }
        return user;
    }

    private void checkPassword() {
        if (passwordRejection != null) {
            throw new PasswordRejectedException(passwordRejection);
        }
    }

    private void ensureUp() {
        if (unavailable) {
            throw new IdentityProviderException("auth is down", null);
        }
    }

    public record User(UUID id, String username, String email, String displayName, String password, boolean enabled,
                       String disabledReason, Set<String> roles, boolean googleLinked) {

        User withEnabled(boolean enabled, String disabledReason) {
            return new User(id, username, email, displayName, password, enabled, disabledReason, roles, googleLinked);
        }

        Account toAccount() {
            return new Account(id, username, email, displayName, roles, enabled, disabledReason, password != null,
                    googleLinked, false, null);
        }
    }
}
