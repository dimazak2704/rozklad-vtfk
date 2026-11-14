package ua.vtc.rozklad.auth.account;

import java.util.Locale;

/**
 * The one normalisation of a sign-in identifier (username or e-mail), shared by the account lookup and the
 * lockout keys of the sign-in journal. Spring's form login passes {@code username.trim()} on, which drops every
 * character up to U+0020, control characters included; the lockout keys must drop exactly the same, or a
 * spelling such as {@code "\u0001admin"} reaches the account with fresh counters and a fresh global gate.
 */
public final class SignInIdentifier {

    /** The longest identifier an account can have: an e-mail address of 254 characters. */
    public static final int MAX_LENGTH = 254;

    private SignInIdentifier() {
    }

    /** Trimmed like the form login ({@link String#trim()}) and in lower case; {@code null} stays {@code null}. */
    public static String normalise(String identifier) {
        return identifier == null ? null : identifier.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a normalised identifier could belong to an account: not blank, at most {@link #MAX_LENGTH}
     * characters and without control characters (usernames are {@code [a-z][a-z0-9._-]}, and e-mails with
     * control characters are refused when an account is saved).
     */
    public static boolean plausible(String normalised) {
        return normalised != null && !normalised.isBlank() && normalised.length() <= MAX_LENGTH
                && normalised.chars().noneMatch(Character::isISOControl);
    }
}
