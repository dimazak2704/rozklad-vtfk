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
import ua.vtc.rozklad.auth.config.AuthServerProperties;

/**
 * Runs before the password check of {@code POST /login} and applies {@link LockoutPolicy}. A refused attempt
 * never reaches BCrypt; it is recorded as {@code LOCKED} or {@code THROTTLED} and sent to
 * {@code /login?error=throttled}. Google sign-in does not pass here, so password failures never block it.
 */
public class LoginThrottleFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottleFilter.class);

    private final RequestMatcher loginPost = PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/login");
    private final RedirectStrategy redirect = new DefaultRedirectStrategy();
    private final LoginAttempts attempts;
    private final AuthServerProperties.Lockout config;
    private final LockoutPolicy policy;
    private final Clock clock;

    public LoginThrottleFilter(LoginAttempts attempts, AuthServerProperties.Lockout config, Clock clock) {
        this.attempts = attempts;
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
        LockoutPolicy.Decision decision = policy.decide(attempts.stats(hash, address.prefix(), now, config), now);
        switch (decision) {
            case LOCKED, THROTTLED -> {
                LoginAttempts.Outcome outcome = decision == LockoutPolicy.Decision.LOCKED
                        ? LoginAttempts.Outcome.LOCKED : LoginAttempts.Outcome.THROTTLED;
                attempts.record(LoginAttempts.Method.PASSWORD, hash, null, address, outcome, now);
                log.info("password sign-in refused: {} from {}", outcome, address.prefix());
                redirect.sendRedirect(request, response, "/login?error=throttled");
                return;
            }
            case SLOW -> {
                log.warn("password sign-in slowed down: many failures of one identifier from all networks");
                sleep();
            }
            case ALLOW -> {
                // далі звичайна перевірка пароля
            }
        }
        chain.doFilter(request, response);
    }

    private void sleep() {
        try {
            Thread.sleep(config.slowdownDelay());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
