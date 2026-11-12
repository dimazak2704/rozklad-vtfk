package ua.vtc.rozklad.auth.login;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Trusted devices (OWASP "device cookies", DESIGN §4.3). After a successful password sign-in the browser gets
 * an {@code HttpOnly} cookie with a random token bound to the identifier it signed in with; the table keeps
 * only SHA-256 of the token and of the identifier. A later attempt that carries the cookie for the same
 * identifier is counted on its own key (the device) and skips the network throttle and the global gate, so
 * other people's failures on a shared network cannot lock the owner out. Failures from a trusted device still
 * lock that device.
 */
@Component
public class LoginDevices {

    /** How long a device stays trusted; the same as the sign-in journal. */
    static final Duration LIFETIME = LoginAttemptRetention.RETENTION;
    /** Request attribute with the token hash of a trusted device for the submitted identifier. */
    static final String TRUSTED = LoginDevices.class.getName() + ".trusted";
    private static final String NAME = "RZ_DEVICE";
    private static final String SECURE_NAME = "__Secure-" + NAME;
    private static final int TOKEN_BYTES = 32;

    private final JdbcClient jdbc;
    private final SecureRandom random = new SecureRandom();

    public LoginDevices(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The token hash of the device cookie when it is still trusted for {@code identifierHash}; also stored in
     * the request for the outcome handlers.
     */
    public Optional<byte[]> trusted(HttpServletRequest request, byte[] identifierHash, Instant now) {
        Optional<byte[]> device = token(request).map(LoginDevices::sha256).filter(tokenHash -> jdbc.sql("""
                SELECT count(*) FROM login_device
                 WHERE token_hash = ? AND identifier_hash = ? AND created_at > ?
                """).params(tokenHash, identifierHash, Timestamp.from(now.minus(LIFETIME))).query(Long.class)
                .single() > 0);
        device.ifPresent(tokenHash -> request.setAttribute(TRUSTED, tokenHash));
        return device;
    }

    /** What {@link #trusted} found for this request, or {@code null}. */
    static byte[] trustedOf(HttpServletRequest request) {
        return request.getAttribute(TRUSTED) instanceof byte[] hash ? hash : null;
    }

    /** Trusts this browser for {@code identifierHash}, unless it already is. */
    public void remember(HttpServletRequest request, HttpServletResponse response, byte[] identifierHash,
            Instant now) {
        byte[] current = trustedOf(request);
        if (current != null) {
            return;
        }
        byte[] token = new byte[TOKEN_BYTES];
        random.nextBytes(token);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        jdbc.sql("INSERT INTO login_device (token_hash, identifier_hash, created_at) VALUES (?, ?, ?)")
                .params(sha256(value), identifierHash, Timestamp.from(now)).update();
        boolean secure = request.isSecure();
        ResponseCookie cookie = ResponseCookie.from(secure ? SECURE_NAME : NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                // браузер шле його лише з формою входу
                .path(request.getContextPath() + "/login")
                .maxAge(LIFETIME)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /** Deletes devices older than the lifetime; returns how many. */
    public int purgeOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM login_device WHERE created_at < ?").param(Timestamp.from(cutoff)).update();
    }

    private static Optional<String> token(HttpServletRequest request) {
        String name = request.isSecure() ? SECURE_NAME : NAME;
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies).filter(c -> name.equals(c.getName())).map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank() && v.length() <= 100).findFirst();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
