package ua.vtc.rozklad.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.ErrorResponseException;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.Employment;
import ua.vtc.rozklad.core.catalog.TeacherInfo;
import ua.vtc.rozklad.core.identity.UserProfile;
import ua.vtc.rozklad.core.shared.security.CurrentUserProvider;
import ua.vtc.rozklad.core.shared.security.KeycloakRealmRoleConverter;

/** The caller and the teacher linked to the account (ownership checks of other modules). */
class TokenCurrentUserServiceTest {

    private static final UUID ACCOUNT = UUID.fromString("a0000000-0000-4000-8000-000000000002");

    private final CatalogQueries catalog = mock(CatalogQueries.class);
    private final TokenCurrentUserService service = new TokenCurrentUserService(new CurrentUserProvider(), catalog);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void linkedTeacherIsResolvedFromTheTokenSubject() {
        UUID teacherId = UUID.randomUUID();
        signIn("TEACHER", "ADMIN");
        given(catalog.teacherByAccount(ACCOUNT)).willReturn(Optional.of(new TeacherInfo(teacherId, "Кренцін", "Микола",
                null, "Кренцін М.Д.", "Кренцін", null, Employment.STAFF, true, ACCOUNT)));

        UserProfile profile = service.profile();

        assertThat(profile.roles()).containsExactly("ADMIN", "TEACHER");
        assertThat(profile.teacher().shortName()).isEqualTo("Кренцін М.Д.");
        assertThat(service.requireTeacherId()).isEqualTo(teacherId);
    }

    @Test
    void accountWithoutTeacherIsForbiddenForOwnershipChecks() {
        signIn("TEACHER");
        given(catalog.teacherByAccount(ACCOUNT)).willReturn(Optional.empty());

        assertThat(service.profile().teacher()).isNull();
        assertThatThrownBy(service::requireTeacherId)
                .isInstanceOfSatisfying(ErrorResponseException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void anonymousCallerIsUnauthenticated() {
        assertThat(service.currentTeacher()).isEmpty();
        assertThatThrownBy(service::requireTeacherId).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    private static void signIn(String... roles) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(ACCOUNT.toString())
                .claim("preferred_username", "krentsin")
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, new KeycloakRealmRoleConverter().convert(jwt)));
    }
}
