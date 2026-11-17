package ua.vtc.rozklad.auth.login;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.auth.account.Account;
import ua.vtc.rozklad.auth.account.AccountRepository;
import ua.vtc.rozklad.auth.config.AuthServerProperties;
import ua.vtc.rozklad.auth.session.GoogleSignInMark;
import ua.vtc.rozklad.auth.session.SessionAttributes;

/**
 * What happens after a password check: the outcome goes to {@code login_attempt}, a success stamps the
 * session with its sign-in time (absolute timeout), trusts the browser for this identifier
 * ({@link LoginDevices}) and continues the saved authorize request. Every failure
 * shows the same generic message.
 */
@Component
public class LoginOutcomeHandlers {

    private static final Logger log = LoggerFactory.getLogger(LoginOutcomeHandlers.class);

    private final AccountRepository accounts;
    private final LoginAttempts attempts;
    private final LoginDevices devices;
    private final Clock clock;
    private final String defaultTarget;

    public LoginOutcomeHandlers(AccountRepository accounts, LoginAttempts attempts, LoginDevices devices, Clock clock,
            AuthServerProperties properties) {
        this.accounts = accounts;
        this.attempts = attempts;
        this.devices = devices;
        this.clock = clock;
        // без збереженого запиту (сторінку входу відкрили напряму) — на /login сайту, який одразу завершить вхід
        this.defaultTarget = stripSlash(properties.publicWebUrl().toString()) + "/login";
    }

    public SavedRequestAwareAuthenticationSuccessHandler successHandler() {
        var handler = new SavedRequestAwareAuthenticationSuccessHandler() {
            @Override
            public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                    Authentication authentication) throws ServletException, IOException {
                Instant now = clock.instant();
                UUID accountId = UUID.fromString(authentication.getName());
                accounts.recordLogin(accountId, now);
                byte[] identifier = LoginAttempts.hash(request.getParameter("username"));
                attempts.record(LoginAttempts.Method.PASSWORD, identifier, accountId, ClientAddress.of(request),
                        LoginDevices.trustedOf(request), LoginAttempts.Outcome.SUCCESS, now);
                // цей браузер далі має власний лічильник блокування для цього логіна
                devices.remember(request, response, identifier, now);
                request.getSession().setAttribute(SessionAttributes.AUTHENTICATED_AT, now.toEpochMilli());
                // останній вхід цієї сесії — паролем: попередній вхід через Google більше нічого не доводить
                GoogleSignInMark.clear(request.getSession(false));
                log.info("password sign-in succeeded for account {}", accountId);
                super.onAuthenticationSuccess(request, response, authentication);
            }
        };
        handler.setDefaultTargetUrl(defaultTarget);
        return handler;
    }

    public AuthenticationFailureHandler failureHandler() {
        var redirect = new DefaultRedirectStrategy();
        return (request, response, exception) -> {
            String identifier = request.getParameter("username");
            Optional<Account> account = accounts.findByIdentifier(identifier);
            LoginAttempts.Outcome outcome = outcome(exception, account);
            attempts.record(LoginAttempts.Method.PASSWORD, LoginAttempts.hash(identifier),
                    account.map(Account::id).orElse(null), ClientAddress.of(request), LoginDevices.trustedOf(request),
                    outcome, clock.instant());
            log.info("password sign-in failed: {}", outcome);
            redirect.sendRedirect(request, response, "/login?error");
        };
    }

    private static LoginAttempts.Outcome outcome(AuthenticationException exception, Optional<Account> account) {
        if (exception instanceof DisabledException) {
            return account.filter(Account::revoked).isPresent()
                    ? LoginAttempts.Outcome.REVOKED : LoginAttempts.Outcome.DISABLED;
        }
        return LoginAttempts.Outcome.BAD_CREDENTIALS;
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
