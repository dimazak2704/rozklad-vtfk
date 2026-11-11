package ua.vtc.rozklad.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import ua.vtc.rozklad.auth.support.AuthIntegrationTest;
import ua.vtc.rozklad.auth.support.Browsers;
import ua.vtc.rozklad.auth.support.OidcClient;
import ua.vtc.rozklad.auth.support.TestAccounts;

/**
 * The password principal survives the Jackson round trip of {@code JdbcOAuth2AuthorizationService} (R3), and
 * no credential ends up in the stored attributes. {@code SessionInformation} is only put into the token
 * context to compute {@code sid}; it is never stored, so it needs no serialisation.
 */
@AuthIntegrationTest
class JdbcAuthorizationRoundTripIT {

    @Autowired
    private Browsers browsers;

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private OAuth2AuthorizationService authorizations;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void thePasswordPrincipalIsReadBackAsTheAccountId() {
        TestAccounts.Created teacher = accounts.teacher();
        OidcClient.Tokens tokens = OidcClient.signIn(browsers.open(), teacher.username(), TestAccounts.PASSWORD);

        OAuth2Authorization authorization = authorizations.findByToken(tokens.accessToken(), OAuth2TokenType.ACCESS_TOKEN);

        assertThat(authorization).isNotNull();
        assertThat(authorization.getPrincipalName()).isEqualTo(teacher.id().toString());
        Object principal = authorization.getAttribute(Principal.class.getName());
        assertThat(principal).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        var authentication = (UsernamePasswordAuthenticationToken) principal;
        assertThat(authentication.getPrincipal()).isInstanceOf(User.class);
        assertThat(((User) authentication.getPrincipal()).getUsername()).isEqualTo(teacher.id().toString());
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority).contains("ROLE_TEACHER");

        String stored = jdbc.sql("SELECT attributes FROM oauth2_authorization WHERE principal_name = ?")
                .param(teacher.id().toString()).query(String.class).single();
        assertThat(stored).doesNotContain("{bcrypt}").doesNotContain(TestAccounts.PASSWORD)
                .doesNotContain("SessionInformation");
    }
}
