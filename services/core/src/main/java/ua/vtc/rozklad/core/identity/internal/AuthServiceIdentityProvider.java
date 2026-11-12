package ua.vtc.rozklad.core.identity.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link IdentityProvider} over the internal accounts API of {@code auth} ({@code /auth/internal/accounts},
 * DESIGN §1.3) with a {@code client_credentials} token of {@code rozklad-core} (scope {@code accounts.manage}).
 * The token is fetched on the first call, so core starts while {@code auth} is down; a call then fails with
 * {@link IdentityProviderException} (503 for the user). Errors of {@code auth} are RFC 9457 problems whose
 * {@code type} ends in a stable code; they map to the exceptions of the port.
 */
@Component
class AuthServiceIdentityProvider implements IdentityProvider {

    /** Header with the admin's {@code preferred_username} for the audit columns of {@code auth}. */
    static final String ACTOR_HEADER = "X-Actor";
    static final String SYSTEM_ACTOR = "rozklad-core";
    private static final int IDS_PER_REQUEST = 100;
    /** The largest page of the internal search ({@code MAX_PAGE_SIZE} of {@code auth}). */
    static final int LIST_PAGE_SIZE = 200;
    private static final ParameterizedTypeReference<List<AccountJson>> ACCOUNT_LIST = new ParameterizedTypeReference<>() {
    };
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Logger log = LoggerFactory.getLogger(AuthServiceIdentityProvider.class);

    private final RestClient accounts;

    AuthServiceIdentityProvider(@Qualifier(AccountsApiConfig.REST_CLIENT) RestClient accounts) {
        this.accounts = accounts;
    }

    @Override
    public UUID createUser(NewAccount account) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", account.username());
        body.put("email", account.email());
        body.put("displayName", account.displayName());
        body.put("roles", account.roles());
        body.put("password", account.password());
        body.put("enabled", true);
        String subject = account.username() != null ? account.username() : account.email();
        Supplier<Void> put = () -> {
            accounts.put().uri("/accounts/{id}", account.id()).body(body).retrieve().toBodilessEntity();
            return null;
        };
        try {
            call("create account", subject, put);
        } catch (IdentityProviderException unreachable) {
            if (!(unreachable.getCause() instanceof ResourceAccessException)) {
                throw unreachable;
            }
            // PUT з тим самим id ідемпотентний: повтор після тайм-ауту не створить другий обліковий запис
            log.warn("auth did not answer the account creation of {}; retrying once", account.id());
            call("create account", subject, put);
        }
        return account.id();
    }

    @Override
    public Optional<Account> findUser(UUID userId) {
        return call("find account", userId.toString(), () -> {
            try {
                return Optional.ofNullable(accounts.get().uri("/accounts/{id}", userId).retrieve()
                        .body(AccountJson.class)).map(AccountJson::toAccount);
            } catch (RestClientResponseException missing) {
                if (missing.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                    return Optional.empty();
                }
                throw missing;
            }
        });
    }

    @Override
    public Optional<Account> findUserByUsername(String username) {
        return call("find account by username", username, () -> first(accounts.get()
                .uri(uri -> uri.path("/accounts").queryParam("username", username).build())
                .retrieve().body(ACCOUNT_LIST)));
    }

    @Override
    public Optional<Account> findUserByEmail(String email) {
        return call("find account by e-mail", email, () -> first(accounts.get()
                .uri(uri -> uri.path("/accounts").queryParam("email", email).build())
                .retrieve().body(ACCOUNT_LIST)));
    }

    @Override
    public List<Account> findUsers(Collection<UUID> ids) {
        List<UUID> unique = ids.stream().distinct().toList();
        List<Account> found = new ArrayList<>();
        for (int from = 0; from < unique.size(); from += IDS_PER_REQUEST) {
            String batch = unique.subList(from, Math.min(unique.size(), from + IDS_PER_REQUEST)).stream()
                    .map(UUID::toString).collect(Collectors.joining(","));
            List<AccountJson> page = call("find accounts", batch, () -> accounts.get()
                    .uri(uri -> uri.path("/accounts").queryParam("ids", batch).build())
                    .retrieve().body(ACCOUNT_LIST));
            if (page != null) {
                page.stream().map(AccountJson::toAccount).forEach(found::add);
            }
        }
        return found;
    }

    @Override
    public List<Account> listUsers() {
        List<Account> found = new ArrayList<>();
        for (int page = 0; ; page++) {
            int current = page;
            List<AccountJson> batch = call("list accounts", "page " + page, () -> accounts.get()
                    .uri(uri -> uri.path("/accounts").queryParam("page", current).queryParam("size", LIST_PAGE_SIZE)
                            .build())
                    .retrieve().body(ACCOUNT_LIST));
            if (batch == null || batch.isEmpty()) {
                return found;
            }
            batch.stream().map(AccountJson::toAccount).forEach(found::add);
            if (batch.size() < LIST_PAGE_SIZE) {
                return found;
            }
        }
    }

    @Override
    public void restoreAccess(UUID userId, String password) {
        setPassword(userId, password);
        enable(userId, false);
    }

    @Override
    public void setPassword(UUID userId, String password) {
        call("set password", userId.toString(), () -> accounts.put().uri("/accounts/{id}/password", userId)
                .body(Map.of("password", password)).retrieve().toBodilessEntity());
    }

    @Override
    public void setUsername(UUID userId, String username) {
        patch(userId, Map.of("username", username), username);
    }

    @Override
    public void setDisplayName(UUID userId, String displayName) {
        patch(userId, Map.of("displayName", displayName), userId.toString());
    }

    @Override
    public void grantRole(UUID userId, String role) {
        Account account = findUser(userId).orElseThrow(() -> new AccountNotFoundException(userId));
        if (account.roles().contains(role)) {
            return;
        }
        Set<String> roles = new TreeSet<>(account.roles());
        roles.add(role);
        setRoles(userId, roles);
    }

    @Override
    public void setRoles(UUID userId, Set<String> roles) {
        patch(userId, Map.of("roles", roles), userId.toString());
    }

    @Override
    public void revoke(UUID userId) {
        call("revoke account", userId.toString(), () -> accounts.post().uri("/accounts/{id}/revoke", userId)
                .retrieve().toBodilessEntity());
    }

    @Override
    public void restore(UUID userId) {
        enable(userId, true);
    }

    @Override
    public void unbindGoogle(UUID userId) {
        call("unbind Google", userId.toString(), () -> accounts.delete()
                .uri("/accounts/{id}/federated/google", userId).retrieve().toBodilessEntity());
    }

    @Override
    public void deleteUser(UUID userId) {
        try {
            call("delete account", userId.toString(), () -> accounts.delete().uri("/accounts/{id}", userId)
                    .retrieve().toBodilessEntity());
        } catch (IdentityProviderException failed) {
            log.error("Could not delete account {} in auth", userId, failed);
            throw failed;
        }
    }

    private void enable(UUID userId, boolean clearRevocation) {
        call("enable account", userId.toString(), () -> accounts.post().uri("/accounts/{id}/enable", userId)
                .body(Map.of("clearRevocation", clearRevocation)).retrieve().toBodilessEntity());
    }

    private void patch(UUID userId, Map<String, Object> changes, String subject) {
        call("update account", subject, () -> accounts.patch().uri("/accounts/{id}", userId).body(changes)
                .retrieve().toBodilessEntity());
    }

    private static Optional<Account> first(List<AccountJson> found) {
        return found == null ? Optional.empty() : found.stream().findFirst().map(AccountJson::toAccount);
    }

    /**
     * Runs a call and maps failures: problems of {@code auth} to the port's exceptions; transport, token and
     * unexpected HTTP failures to {@link IdentityProviderException}.
     */
    private static <T> T call(String operation, String subject, Supplier<T> action) {
        try {
            return action.get();
        } catch (RestClientResponseException answered) {
            throw translate(operation, subject, answered);
        } catch (RestClientException | OAuth2AuthorizationException | IllegalStateException failed) {
            throw new IdentityProviderException("auth call failed: " + operation, failed);
        }
    }

    static RuntimeException translate(String operation, String subject, RestClientResponseException answered) {
        int status = answered.getStatusCode().value();
        JsonNode problem = problem(answered);
        String type = problem.path("type").asString("");
        String code = type.substring(type.lastIndexOf(':') + 1);
        if (status == 409) {
            switch (code) {
                case "username-taken" -> {
                    return new UsernameTakenException(subject);
                }
                case "email-taken" -> {
                    return new EmailTakenException(subject);
                }
                case "last-admin" -> {
                    return new LastAdminException();
                }
                case "account-revoked" -> {
                    return new AccountRevokedException(uuidOrNull(subject));
                }
                default -> {
                    // інші 409 — помилка контракту, а не правило
                }
            }
        }
        if (status == 400 && "password-rejected".equals(code)) {
            return new PasswordRejectedException(problem.path("reason").asString("unknown"));
        }
        if (status == 404) {
            return new AccountNotFoundException(uuidOrNull(subject));
        }
        return new IdentityProviderException("auth answered " + status + " (" + code + ") to " + operation, answered);
    }

    private static JsonNode problem(RestClientResponseException answered) {
        try {
            return JSON.readTree(answered.getResponseBodyAsString());
        } catch (RuntimeException unreadable) {
            return JSON.createObjectNode();
        }
    }

    private static UUID uuidOrNull(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    /** The admin's {@code preferred_username} from the request token; {@code rozklad-core} otherwise (invites). */
    static String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String username = jwt.getClaimAsString("preferred_username");
            if (username != null && !username.isBlank()) {
                return username;
            }
        }
        return SYSTEM_ACTOR;
    }

    /** {@code AccountDto} of {@code auth}; unknown fields are ignored. */
    record AccountJson(UUID id, String username, String email, String displayName, Set<String> roles,
                       boolean enabled, String disabledReason, boolean hasPassword, boolean demo, Google google,
                       Instant lastLoginAt) {

        Account toAccount() {
            return new Account(id, username, email, displayName, roles, enabled, disabledReason, hasPassword,
                    google != null && google.linked(), demo, lastLoginAt);
        }

        record Google(boolean linked) {
        }
    }
}
