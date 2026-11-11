package ua.vtc.rozklad.auth.google;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.auth.config.AuthServerProperties;
import ua.vtc.rozklad.auth.login.ClientAddress;
import ua.vtc.rozklad.auth.login.LoginAttempts;
import ua.vtc.rozklad.auth.session.SessionAttributes;

/**
 * After Google: a success becomes the same principal as a password sign-in (D9: a {@link User} named by the
 * account UUID), so the token customizer, the stored authorization and the session index see one type; then
 * the saved authorize request continues. A failure goes back to the sign-in page with one of two messages;
 * the saved authorize request stays in the session, so a password sign-in still completes it. Redirect
 * targets are context-relative ({@code /login?…} becomes {@code /auth/login?…}).
 */
@Component
public class GoogleLoginHandlers {

    private static final Logger log = LoggerFactory.getLogger(GoogleLoginHandlers.class);

    /** Refusals that show «Цієї адреси немає у списку доступу…». */
    static final Set<String> NOT_ALLOWED = Set.of("not_allowed", "domain_mismatch", "disabled", "revoked",
            "identity_conflict");
    /** Codes the allowlist has already written to the journal. */
    private static final Set<String> RECORDED = Set.of("not_allowed", "domain_mismatch", "disabled", "revoked",
            "identity_conflict", "email_unverified");

    private final LoginAttempts attempts;
    private final Clock clock;
    private final String defaultTarget;
    private final SecurityContextRepository contexts = new DelegatingSecurityContextRepository(
            new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    private final RedirectStrategy redirect = new DefaultRedirectStrategy();

    public GoogleLoginHandlers(LoginAttempts attempts, Clock clock, AuthServerProperties properties) {
        this.attempts = attempts;
        this.clock = clock;
        String web = properties.publicWebUrl().toString();
        this.defaultTarget = (web.endsWith("/") ? web.substring(0, web.length() - 1) : web) + "/login";
    }

    public AuthenticationSuccessHandler successHandler() {
        var saved = new SavedRequestAwareAuthenticationSuccessHandler();
        saved.setDefaultTargetUrl(defaultTarget);
        return (request, response, authentication) -> {
            Authentication normalised = normalise(authentication, clock.instant());
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(normalised);
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
            request.getSession().setAttribute(SessionAttributes.AUTHENTICATED_AT, clock.millis());
            saved.onAuthenticationSuccess(request, response, normalised);
        };
    }

    public AuthenticationFailureHandler failureHandler() {
        return this::onFailure;
    }

    /**
     * {@code UsernamePasswordAuthenticationToken(User(accountId))}, what form login produces, plus the factor
     * authority with the sign-in time: Spring AS takes {@code auth_time} of the ID token from it.
     */
    static Authentication normalise(Authentication google, Instant signedInAt) {
        if (!(google.getPrincipal() instanceof GoogleAccountUser user)) {
            throw new IllegalStateException("Google sign-in without an allowlisted account");
        }
        List<GrantedAuthority> roles = google.getAuthorities().stream()
                .filter(authority -> !(authority instanceof FactorGrantedAuthority))
                .map(GrantedAuthority.class::cast).toList();
        User principal = new User(user.accountId().toString(), "", roles);
        principal.eraseCredentials();
        List<GrantedAuthority> authorities = new ArrayList<>(roles);
        authorities.add(FactorGrantedAuthority.withAuthority(FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY)
                .issuedAt(signedInAt).build());
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    }

    private void onFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        String code = exception instanceof OAuth2AuthenticationException oauth ? oauth.getError().getErrorCode() : "";
        if (!RECORDED.contains(code)) {
            // відмова чи помилка Google (скасований вхід, недійсний ID token): окремо від рішень списку доступу
            attempts.record(LoginAttempts.Method.GOOGLE, null, null, ClientAddress.of(request),
                    LoginAttempts.Outcome.PROVIDER_ERROR, clock.instant());
            log.info("google sign-in failed at the provider step: {} {}", exception.getClass().getSimpleName(),
                    code.isEmpty() ? exception.getMessage() : code);
        }
        redirect.sendRedirect(request, response,
                NOT_ALLOWED.contains(code) ? "/login?error=not_allowed" : "/login?error=google");
    }
}
