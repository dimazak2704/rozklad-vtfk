package ua.vtc.rozklad.auth.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a sign-in session older than the absolute lifetime ({@code app.auth.session.max-lifetime}), however
 * active it is. Runs after Spring Session's filter and before Spring Security, so the request continues as
 * anonymous: the authorize endpoint then answers {@code login_required} to a silent renew or shows the
 * sign-in page.
 */
public class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AbsoluteSessionTimeoutFilter.class);

    private final Duration maxLifetime;
    private final Clock clock;

    public AbsoluteSessionTimeoutFilter(Duration maxLifetime, Clock clock) {
        this.maxLifetime = maxLifetime;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SessionAttributes.AUTHENTICATED_AT) instanceof Long signedInAt
                && clock.millis() - signedInAt > maxLifetime.toMillis()) {
            session.invalidate();
            log.info("sign-in session ended: absolute lifetime {} reached", maxLifetime);
        }
        chain.doFilter(request, response);
    }
}
