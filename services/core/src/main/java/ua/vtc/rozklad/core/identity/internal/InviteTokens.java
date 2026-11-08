package ua.vtc.rozklad.core.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Invite tokens: 32 random bytes as URL-safe Base64 (43 characters) in the link; the database
 * keeps only the SHA-256 hex of the token, so a leaked table cannot be used to register.
 */
final class InviteTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    private InviteTokens() {
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", missing);
        }
    }

    /** Anything else cannot be a token we issued, so it is answered without a database lookup. */
    static boolean hasValidFormat(String token) {
        return token != null && FORMAT.matcher(token).matches();
    }
}
