package ua.vtc.rozklad.auth.google;

import java.util.Collection;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

/**
 * A Google user the allowlist accepted, with the account it signs in as. Lives only until
 * {@link GoogleLoginHandlers} replaces it with the same principal a password sign-in has (D9).
 */
public class GoogleAccountUser extends DefaultOidcUser {

    private static final long serialVersionUID = 1L;

    private final UUID accountId;

    GoogleAccountUser(UUID accountId, Collection<? extends GrantedAuthority> authorities, OidcIdToken idToken,
            OidcUserInfo userInfo) {
        super(authorities, idToken, userInfo);
        this.accountId = accountId;
    }

    public UUID accountId() {
        return accountId;
    }
}
