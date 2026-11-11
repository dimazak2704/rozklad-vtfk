package ua.vtc.rozklad.core.identity.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.identity.InviteStatus;

/** DTOs of invites (admin and public) and of the internal teacher endpoint. */
public final class InviteDtos {

    /** Password rules of the sign-in service (OWASP ASVS 2.1.1); {@code auth} enforces them, core checks early. */
    public static final int MIN_PASSWORD = 12;
    public static final int MAX_PASSWORD = 64;
    /** BCrypt reads 72 bytes; a Cyrillic letter takes two in UTF-8. */
    public static final int MAX_PASSWORD_CYRILLIC = 36;

    private InviteDtos() {
    }

    public record CreateInviteRequest(@NotNull UUID teacherId) {
    }

    /** The token is shown once: only its hash is stored. */
    public record InviteCreatedDto(UUID id, String token, String url, Instant expiresAt, TeacherRef teacher) {

        @Override
        public String toString() {
            return "InviteCreatedDto[id=" + id + ", token=***]";
        }
    }

    /** Element of {@code GET /api/admin/invites}; the link cannot be shown again. */
    public record InviteDto(UUID id, TeacherRef teacher, InviteStatus status, Instant expiresAt, Instant usedAt,
                            Instant revokedAt, Instant createdAt, String createdBy) {
    }

    /** {@code GET /api/public/invites/{token}}: {@code valid} = status ACTIVE. */
    public record InviteInfoDto(boolean valid, InviteStatus status, String teacherName, Instant expiresAt) {
    }

    public record AcceptInviteRequest(
            @NotBlank
            @Pattern(regexp = "[A-Za-z][A-Za-z0-9._-]{2,49}",
                    message = "Ім'я користувача — 3–50 латинських літер, цифр або «.», «_», «-»; починається з літери")
            String username,
            @NotBlank @Size(min = MIN_PASSWORD, max = MAX_PASSWORD,
                    message = "Пароль має містити від " + MIN_PASSWORD + " до " + MAX_PASSWORD + " символів")
            String password) {

        @Override
        public String toString() {
            return "AcceptInviteRequest[username=" + username + ", password=***]";
        }
    }

    public record AcceptedInviteDto(String username) {
    }

    /**
     * {@code GET /api/internal/teachers/{id}}: the teacher and the link to the account.
     * {@code keycloakUserId} repeats {@code accountId} for old readers and goes away in the next
     * contract version.
     */
    public record InternalTeacherDto(UUID id, String lastName, String firstName, String middleName, String shortName,
                                     String displayName, String cycleCommission, boolean active, UUID accountId,
                                     @Deprecated UUID keycloakUserId, boolean linked) {
    }
}
