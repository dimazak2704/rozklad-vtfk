package ua.vtc.rozklad.auth.google;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ua.vtc.rozklad.auth.account.Account;
import ua.vtc.rozklad.auth.login.ClientAddress;
import ua.vtc.rozklad.auth.login.LoginAttempts;

/**
 * Loads the Google user (Spring's {@link OidcUserService}: userinfo and its {@code sub} check) and lets
 * {@link GoogleAllowlist} decide. Every decision goes to {@code login_attempt}; a refusal is an
 * {@link OAuth2AuthenticationException} whose error code is the outcome in lower case, which
 * {@link GoogleLoginHandlers} turns into the message of the sign-in page.
 */
@Component
public class AllowlistOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private static final Logger log = LoggerFactory.getLogger(AllowlistOidcUserService.class);

    private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;
    private final GoogleAllowlist allowlist;
    private final LoginAttempts attempts;
    private final Clock clock;

    @Autowired
    public AllowlistOidcUserService(GoogleAllowlist allowlist, LoginAttempts attempts, Clock clock) {
        this(new OidcUserService(), allowlist, attempts, clock);
    }

    AllowlistOidcUserService(OAuth2UserService<OidcUserRequest, OidcUser> delegate, GoogleAllowlist allowlist,
            LoginAttempts attempts, Clock clock) {
        this.delegate = delegate;
        this.allowlist = allowlist;
        this.attempts = attempts;
        this.clock = clock;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcUser google = delegate.loadUser(request);
        GoogleIdentity identity = GoogleIdentity.of(google.getIdToken());
        Instant now = clock.instant();
        GoogleAllowlist.Decision decision = allowlist.decide(identity, now);
        attempts.record(LoginAttempts.Method.GOOGLE, LoginAttempts.hash(identity.email()), decision.accountId(),
                clientAddress(), decision.outcome(), now);
        if (!decision.allowed()) {
            log.info("google sign-in refused: {}", decision.outcome());
            throw new OAuth2AuthenticationException(new OAuth2Error(errorCode(decision.outcome())));
        }
        Account account = decision.account();
        log.info("google sign-in accepted for account {}", account.id());
        List<SimpleGrantedAuthority> authorities = account.roles().stream().sorted()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        return new GoogleAccountUser(account.id(), authorities, google.getIdToken(), google.getUserInfo());
    }

    static String errorCode(LoginAttempts.Outcome outcome) {
        return outcome.name().toLowerCase(Locale.ROOT);
    }

    private static ClientAddress clientAddress() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return ClientAddress.of(attributes.getRequest());
        }
        return new ClientAddress(null, null);
    }
}
