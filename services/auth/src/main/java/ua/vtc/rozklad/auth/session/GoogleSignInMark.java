package ua.vtc.rozklad.auth.session;

import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * What a sign-in session says about its last Google sign-in: which account the session is signed in as now,
 * which account Google signed it in as, and when. Everything comes from server-side session attributes written by
 * the sign-in handlers; nothing the client sends goes in.
 *
 * @param sessionAccount the principal name (account id) of the session's security context, or {@code null}
 * @param googleAccount  the account id of the last Google sign-in of this session
 * @param at             when our server finished that Google sign-in (ID token checked, allowlist passed)
 */
public record GoogleSignInMark(String sessionAccount, String googleAccount, Instant at) {

    /** The mark of the session, if it has completed a Google sign-in. */
    public static Optional<GoogleSignInMark> of(HttpSession session) {
        if (session == null) {
            return Optional.empty();
        }
        try {
            if (!(session.getAttribute(SessionAttributes.GOOGLE_AUTHENTICATED_AT) instanceof Long at)
                    || !(session.getAttribute(SessionAttributes.GOOGLE_ACCOUNT_ID) instanceof String account)) {
                return Optional.empty();
            }
            return Optional.of(new GoogleSignInMark(principalName(session), account, Instant.ofEpochMilli(at)));
        } catch (IllegalStateException invalidated) {
            return Optional.empty();
        }
    }

    /** Forgets the Google sign-in of the session (a password sign-in replaced it, or it was used up). */
    public static void clear(HttpSession session) {
        if (session == null) {
            return;
        }
        try {
            session.removeAttribute(SessionAttributes.GOOGLE_AUTHENTICATED_AT);
            session.removeAttribute(SessionAttributes.GOOGLE_ACCOUNT_ID);
        } catch (IllegalStateException invalidated) {
            // сесію вже завершено: нічого забувати
        }
    }

    /**
     * Whether this session proves a fresh Google sign-in of {@code accountId}: the session is still signed in as
     * that account, Google signed it in as that account, and no longer than {@code window} ago (a mark from the
     * future beyond a small clock tolerance does not count).
     */
    public boolean freshFor(UUID accountId, Instant now, Duration window) {
        String expected = accountId.toString();
        return expected.equals(sessionAccount) && expected.equals(googleAccount)
                && !at.isBefore(now.minus(window)) && !at.isAfter(now.plusSeconds(5));
    }

    private static String principalName(HttpSession session) {
        Object context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (context instanceof SecurityContext securityContext) {
            Authentication authentication = securityContext.getAuthentication();
            return authentication == null || !authentication.isAuthenticated() ? null : authentication.getName();
        }
        return null;
    }
}
