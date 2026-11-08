package ua.vtc.rozklad.core.identity.internal;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory Keycloak for tests: users with password, enabled flag and realm roles. */
public class FakeIdentityProvider implements IdentityProvider {

    private final Map<UUID, User> users = new ConcurrentHashMap<>();
    private volatile boolean unavailable;
    private volatile boolean failRoleGrant;

    /** A user that already exists in the realm (e.g. a demo account). */
    public UUID existingUser(String username, String password, boolean enabled) {
        UUID id = UUID.randomUUID();
        users.put(id, new User(id, username, password, enabled, new HashSet<>()));
        return id;
    }

    public void goDown() {
        unavailable = true;
    }

    public void failNextRoleGrant() {
        failRoleGrant = true;
    }

    public Optional<User> user(UUID id) {
        return Optional.ofNullable(users.get(id));
    }

    public Optional<User> byUsername(String username) {
        return users.values().stream().filter(user -> user.username().equals(username)).findFirst();
    }

    public int count() {
        return users.size();
    }

    @Override
    public UUID createUser(NewAccount account) {
        ensureUp();
        if (byUsername(account.username()).isPresent()) {
            throw new UsernameTakenException(account.username());
        }
        UUID id = UUID.randomUUID();
        users.put(id, new User(id, account.username(), account.password(), true, new HashSet<>()));
        return id;
    }

    @Override
    public Optional<Account> findUser(UUID userId) {
        ensureUp();
        return user(userId).map(user -> new Account(user.id(), user.username(), user.enabled()));
    }

    @Override
    public Optional<Account> findUserByUsername(String username) {
        ensureUp();
        return users.values().stream()
                .filter(user -> user.username().equalsIgnoreCase(username))
                .findFirst()
                .map(user -> new Account(user.id(), user.username(), user.enabled()));
    }

    @Override
    public void restoreAccess(UUID userId, String password) {
        ensureUp();
        User user = users.get(userId);
        users.put(userId, new User(userId, user.username(), password, true, user.roles()));
    }

    @Override
    public void grantRealmRole(UUID userId, String role) {
        ensureUp();
        if (failRoleGrant) {
            failRoleGrant = false;
            throw new IdentityProviderException("role mapping failed", null);
        }
        users.get(userId).roles().add(role);
    }

    @Override
    public void deleteUser(UUID userId) {
        users.remove(userId);
    }

    private void ensureUp() {
        if (unavailable) {
            throw new IdentityProviderException("Keycloak is down", null);
        }
    }

    public record User(UUID id, String username, String password, boolean enabled, Set<String> roles) {
    }
}
