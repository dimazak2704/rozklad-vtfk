package ua.vtc.rozklad.auth.account;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A person who may sign in. {@code id} is the principal name everywhere and the {@code sub} of tokens.
 *
 * @param passwordHash {@code {bcrypt}…}, or {@code null} when the account has no password login
 * @param disabledReason {@code REVOKED} (only an explicit restore clears it) or {@code DEMO_OFF}
 */
public record Account(
        UUID id,
        String username,
        String email,
        String displayName,
        String passwordHash,
        boolean enabled,
        String disabledReason,
        boolean demo,
        Set<String> roles,
        Instant lastLoginAt) {

    public static final String REVOKED = "REVOKED";

    /** {@code preferred_username}: the username, else the e-mail. */
    public String loginName() {
        return username != null ? username : email;
    }

    public boolean revoked() {
        return REVOKED.equals(disabledReason);
    }

    public boolean hasPassword() {
        return passwordHash != null;
    }
}
