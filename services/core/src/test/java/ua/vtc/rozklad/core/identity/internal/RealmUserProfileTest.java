package ua.vtc.rozklad.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link KeycloakIdentityProvider#createUser} sends only username, names and password: most teachers of
 * the Excel have initials only and nobody has an e-mail. The realm user profile must therefore not
 * require e-mail or first name, or Keycloak stops the invited teacher at «Update account information»
 * («Account is not fully set up» for the password grant). The realm files (first import) and the
 * profile that {@code dev-stack.sh up} / {@code keycloak-sync.sh} apply to existing realms must agree.
 */
class RealmUserProfileTest {

    private static final Path INFRA = Path.of("..", "..", "infra");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"keycloak/rozklad-realm.json", "deploy/keycloak/rozklad-realm.prod.json"})
    void realmImportCarriesTheSyncedUserProfile(String realmFile) throws IOException {
        JsonNode realm = read(INFRA.resolve(realmFile));
        JsonNode provider = realm.path("components").path("org.keycloak.userprofile.UserProfileProvider").path(0);

        assertThat(provider.path("providerId").asString()).isEqualTo("declarative-user-profile");
        JsonNode imported = JSON.readTree(provider.path("config").path("kc.user.profile.config").path(0).asString());
        assertThat(imported).isEqualTo(read(INFRA.resolve("keycloak/user-profile.json")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "firstName"})
    void attributesCoreDoesNotKnowAreOptional(String attribute) throws IOException {
        JsonNode profile = read(INFRA.resolve("keycloak/user-profile.json"));

        JsonNode definition = null;
        for (JsonNode candidate : profile.path("attributes")) {
            if (attribute.equals(candidate.path("name").asString())) {
                definition = candidate;
            }
        }
        assertThat(definition).as(attribute).isNotNull();
        assertThat(definition.has("required")).as(attribute + " must not be required").isFalse();
    }

    private static JsonNode read(Path file) throws IOException {
        return JSON.readTree(Files.readString(file));
    }
}
