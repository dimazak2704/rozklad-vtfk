package ua.vtc.rozklad.auth.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An account as core sees it (DESIGN §1.3). Never carries a password hash. */
public record AccountDto(
        UUID id,
        String username,
        String email,
        String displayName,
        List<String> roles,
        boolean enabled,
        String disabledReason,
        boolean hasPassword,
        boolean demo,
        Google google,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt) {

    public record Google(boolean linked, String email, Instant linkedAt) {
    }
}
