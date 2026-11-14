package ua.vtc.rozklad.auth.login;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import ua.vtc.rozklad.auth.account.SignInIdentifier;
import ua.vtc.rozklad.auth.config.AuthServerProperties;

/**
 * Runs before the password check of {@code POST /login} and applies {@link LockoutPolicy}. A refused attempt
 * never reaches BCrypt; it is recorded as {@code LOCKED} or {@code THROTTLED} and sent to
 * {@code /login?error=throttled}. Behind the global gate only one attempt per gate delay goes on to the
 * password check, however many arrive in parallel. An allowed attempt whose identifier no account can have
 * (blank, longer than an e-mail, a control character inside; {@link SignInIdentifier#plausible}) fails at once
 * as {@code BAD_CREDENTIALS}, also without BCrypt. Google sign-in does not pass here, so password failures
 * never block it.
 */
public class LoginThrottleFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottleFilter.class);

    private final RequestMatcher loginPost = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/login");
    private final RedirectStrategy redirect = new DefaultRedirectStrategy();
    private final LoginAttempts attempts;
    private final LoginDevices devices;
    private final AuthServerProperties.Lockout config;
    private final LockoutPolicy policy;
    private final Clock clock;

    public LoginThrottleFilter(LoginAttempts attempts, LoginDevices devices, AuthServerProperties.Lockout config,
            Clock clock) {
        this.attempts = attempts;
        this.devices = devices;
        this.config = config;
        this.policy = new LockoutPolicy(config);
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !loginPost.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] hash = LoginAttempts.hash(request.getParameter("username"));
        ClientAddress address = ClientAddress.of(request);
        Instant now = clock.instant();
        byte[] device = devices.trusted(request, hash, now).orElse(null);
        LockoutPolicy.Stats stats = attempts.stats(hash, address.prefix(), device, now, config);
        LockoutPolicy.Decision decision = policy.decide(stats, now);
        if (decision == LockoutPolicy.Decision.GATED && !attempts.passGate(hash, now, policy.gateDelay(stats))) {
            log.warn("password sign-in refused by the global gate: many failures of one identifier from all networks");
            decision = LockoutPolicy.Decision.THROTTLED;
        }
        if (decision == LockoutPolicy.Decision.LOCKED || decision == LockoutPolicy.Decision.THROTTLED) {
            LoginAttempts.Outcome outcome = decision == LockoutPolicy.Decision.LOCKED
                    ? LoginAttempts.Outcome.LOCKED : LoginAttempts.Outcome.THROTTLED;
            attempts.record(LoginAttempts.Method.PASSWORD, hash, null, address, device, outcome, now);
            log.info("password sign-in refused: {} from {}", outcome, address.prefix());
            redirect.sendRedirect(request, response, "/login?error=throttled");
            return;
        }
        if (!SignInIdentifier.plausible(SignInIdentifier.normalise(request.getParameter("username")))) {
            // жоден акаунт не має такого логіна: звичайна невдача, але без BCrypt
            attempts.record(LoginAttempts.Method.PASSWORD, hash, null, address, device,
                    LoginAttempts.Outcome.BAD_CREDENTIALS, now);
            log.info("password sign-in failed: {} (an identifier no account can have)",
                    LoginAttempts.Outcome.BAD_CREDENTIALS);
            redirect.sendRedirect(request, response, "/login?error");
            return;
        }
        chain.doFilter(request, response);
    }
}
