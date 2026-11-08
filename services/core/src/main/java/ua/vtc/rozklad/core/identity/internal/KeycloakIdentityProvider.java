package ua.vtc.rozklad.core.identity.internal;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.JacksonProvider;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.spi.ResteasyClientClassicProvider;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.core.shared.security.KeycloakProperties;

/**
 * {@link IdentityProvider} on the Keycloak Admin REST API with the service account
 * {@code rozklad-core-admin} (client credentials; realm-management {@code manage-users},
 * {@code view-users}). The client is created on first use, so core starts without Keycloak and
 * without the secret; a call then fails with {@link IdentityProviderException} (503 for the user).
 */
@Component
class KeycloakIdentityProvider implements IdentityProvider, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(KeycloakIdentityProvider.class);
    private static final long CONNECT_TIMEOUT_SECONDS = 5;
    private static final long READ_TIMEOUT_SECONDS = 15;

    private final KeycloakProperties properties;
    private volatile Keycloak keycloak;

    KeycloakIdentityProvider(KeycloakProperties properties) {
        this.properties = properties;
    }

    @Override
    public UUID createUser(NewAccount account) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(account.username());
        user.setEnabled(true);
        user.setFirstName(account.firstName());
        user.setLastName(account.lastName());
        UUID id = call("create user", () -> {
            try (Response response = realm().users().create(user)) {
                return switch (response.getStatus()) {
                    case 201 -> UUID.fromString(CreatedResponseUtil.getCreatedId(response));
                    case 409 -> throw new UsernameTakenException(account.username());
                    default -> throw new IdentityProviderException(
                            "Keycloak answered " + response.getStatus() + " to user creation", null);
                };
            }
        });
        try {
            setPassword(id, account.password());
        } catch (RuntimeException failed) {
            // користувач без пароля нікому не потрібен: прибираємо, щоб ім'я не лишалося зайнятим
            try {
                deleteUser(id);
            } catch (RuntimeException cleanupFailed) {
                failed.addSuppressed(cleanupFailed);
            }
            throw failed;
        }
        return id;
    }

    @Override
    public Optional<Account> findUser(UUID userId) {
        return call("find user", () -> {
            try {
                UserRepresentation user = realm().users().get(userId.toString()).toRepresentation();
                return Optional.of(new Account(userId, user.getUsername(), Boolean.TRUE.equals(user.isEnabled())));
            } catch (jakarta.ws.rs.NotFoundException missing) {
                return Optional.empty();
            }
        });
    }

    @Override
    public void restoreAccess(UUID userId, String password) {
        setPassword(userId, password);
        call("enable user", () -> {
            UserResource resource = realm().users().get(userId.toString());
            UserRepresentation user = resource.toRepresentation();
            if (!Boolean.TRUE.equals(user.isEnabled())) {
                user.setEnabled(true);
                resource.update(user);
            }
            return null;
        });
    }

    @Override
    public void grantRealmRole(UUID userId, String role) {
        call("grant realm role", () -> {
            RoleScopeResource realmRoles = realm().users().get(userId.toString()).roles().realmLevel();
            if (realmRoles.listAll().stream().anyMatch(assigned -> role.equals(assigned.getName()))) {
                return null;
            }
            // доступні ролі видно з правом view-users, тож окреме право view-realm не потрібне
            RoleRepresentation available = realmRoles.listAvailable().stream()
                    .filter(candidate -> role.equals(candidate.getName()))
                    .findFirst()
                    .orElseThrow(() -> new IdentityProviderException("Realm role " + role + " is not available", null));
            realmRoles.add(List.of(available));
            return null;
        });
    }

    @Override
    public void deleteUser(UUID userId) {
        try {
            call("delete user", () -> {
                try (Response response = realm().users().delete(userId.toString())) {
                    int status = response.getStatus();
                    if (status != 204 && status != 404) {
                        throw new IdentityProviderException("Keycloak answered " + status + " to user deletion", null);
                    }
                    return null;
                }
            });
        } catch (IdentityProviderException failed) {
            log.error("Could not delete Keycloak user {}; remove it in the admin console", userId, failed);
            throw failed;
        }
    }

    @Override
    public void destroy() {
        Keycloak client = keycloak;
        if (client != null) {
            client.close();
        }
    }

    private void setPassword(UUID userId, String password) {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);
        credential.setTemporary(false);
        call("set password", () -> {
            try {
                realm().users().get(userId.toString()).resetPassword(credential);
                return null;
            } catch (BadRequestException rejected) {
                throw new PasswordRejectedException(errorCode(rejected));
            }
        });
    }

    /** Runs a Keycloak call and turns transport and HTTP failures into {@link IdentityProviderException}. */
    private <T> T call(String operation, KeycloakCall<T> action) {
        try {
            return action.run();
        } catch (UsernameTakenException | PasswordRejectedException | IdentityProviderException expected) {
            throw expected;
        } catch (ProcessingException | WebApplicationException | IllegalStateException failed) {
            throw new IdentityProviderException("Keycloak call failed: " + operation, failed);
        }
    }

    private RealmResource realm() {
        Keycloak client = keycloak;
        if (client == null) {
            synchronized (this) {
                client = keycloak;
                if (client == null) {
                    client = createClient();
                    keycloak = client;
                }
            }
        }
        return client.realm(properties.realm());
    }

    private Keycloak createClient() {
        String secret = properties.admin().clientSecret();
        if (secret == null || secret.isBlank()) {
            throw new IdentityProviderException("CORE_ADMIN_CLIENT_SECRET is not configured", null);
        }
        Client http = ResteasyClientClassicProvider.createClientBuilder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .register(JacksonProvider.class, 100)
                .build();
        return KeycloakBuilder.builder()
                .serverUrl(properties.url().toString())
                .realm(properties.realm())
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .clientId(properties.admin().clientId())
                .clientSecret(secret)
                .resteasyClient(http)
                .build();
    }

    private static String errorCode(WebApplicationException error) {
        try {
            return error.getResponse().readEntity(String.class);
        } catch (RuntimeException unreadable) {
            return "HTTP " + error.getResponse().getStatus();
        }
    }

    @FunctionalInterface
    private interface KeycloakCall<T> {
        T run();
    }
}
