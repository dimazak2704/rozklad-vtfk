package ua.vtc.rozklad.auth.oauth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.oidc.authentication.OidcLogoutAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.oidc.web.authentication.OidcLogoutAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * OIDC RP-initiated logout (DESIGN §4.5): before the default handler ends the session and redirects, the
 * authorization that holds the presented ID token is deleted. Other devices keep theirs, so their logout
 * still works. Access tokens already issued live until {@code exp}.
 */
public class LogoutAuthorizationCleanup implements AuthenticationSuccessHandler {

    private static final OAuth2TokenType ID_TOKEN = new OAuth2TokenType(OidcParameterNames.ID_TOKEN);

    private final OAuth2AuthorizationService authorizations;
    private final AuthenticationSuccessHandler delegate = new OidcLogoutAuthenticationSuccessHandler();

    public LogoutAuthorizationCleanup(OAuth2AuthorizationService authorizations) {
        this.authorizations = authorizations;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        if (authentication instanceof OidcLogoutAuthenticationToken logout && logout.getIdToken() != null) {
            OAuth2Authorization authorization = authorizations.findByToken(logout.getIdToken().getTokenValue(), ID_TOKEN);
            if (authorization != null) {
                authorizations.remove(authorization);
            }
        }
        delegate.onAuthenticationSuccess(request, response, authentication);
    }
}
