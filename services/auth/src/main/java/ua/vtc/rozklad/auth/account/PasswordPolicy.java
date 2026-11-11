package ua.vtc.rozklad.auth.account;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import ua.vtc.rozklad.auth.config.AuthServerProperties;

/**
 * Password rules (OWASP ASVS 2.1): at least {@code min-length} characters, at most 64 characters and
 * 72 UTF-8 bytes (BCrypt ignores the rest), not the username or e-mail, not a common password. The
 * internal API, the bootstrap and the prod demo seed use it; core maps the reason to Ukrainian text.
 */
@Component
public class PasswordPolicy {

    public static final int MAX_LENGTH = 64;
    public static final int MAX_BYTES = 72;

    private final int minLength;
    private final Set<String> common;

    @Autowired
    public PasswordPolicy(AuthServerProperties properties) {
        this(properties.password().minLength(), loadCommon());
    }

    PasswordPolicy(int minLength, Set<String> common) {
        this.minLength = minLength;
        this.common = common;
    }

    /** The reason the password is rejected, or empty when it is acceptable. */
    public Optional<Reason> check(String password, String username, String email) {
        if (password == null || password.codePointCount(0, password.length()) < minLength) {
            return Optional.of(Reason.TOO_SHORT);
        }
        if (password.codePointCount(0, password.length()) > MAX_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return Optional.of(Reason.TOO_LONG);
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (username != null && lower.equals(username.trim().toLowerCase(Locale.ROOT))) {
            return Optional.of(Reason.SAME_AS_USERNAME);
        }
        if (email != null && (lower.equals(email.trim().toLowerCase(Locale.ROOT))
                || lower.equals(localPart(email)))) {
            return Optional.of(Reason.SAME_AS_EMAIL);
        }
        if (common.contains(lower) || oneRepeatedCharacter(password)) {
            return Optional.of(Reason.TOO_COMMON);
        }
        return Optional.empty();
    }

    public int minLength() {
        return minLength;
    }

    private static String localPart(String email) {
        int at = email.indexOf('@');
        return (at > 0 ? email.substring(0, at) : email).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean oneRepeatedCharacter(String password) {
        return password.codePoints().distinct().count() == 1;
    }

    private static Set<String> loadCommon() {
        var resource = new ClassPathResource("auth/common-passwords.txt");
        Set<String> result = new HashSet<>();
        try (var reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.strip();
                if (!value.isEmpty() && !value.startsWith("#")) {
                    result.add(value.toLowerCase(Locale.ROOT));
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read auth/common-passwords.txt", ex);
        }
        return Set.copyOf(result);
    }

    /** Stable codes of the internal API ({@code password-rejected} problem, field {@code reason}). */
    public enum Reason {
        TOO_SHORT("too-short"),
        TOO_LONG("too-long"),
        SAME_AS_USERNAME("same-as-username"),
        SAME_AS_EMAIL("same-as-email"),
        TOO_COMMON("too-common");

        private final String code;

        Reason(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
