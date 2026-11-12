package ua.vtc.rozklad.core.identity.internal;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherNames;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.Account;

/** DTOs of «Доступи» ({@code /api/admin/access}, DESIGN §1.5). */
public final class AccessDtos {

    private AccessDtos() {
    }

    /** {@code ACTIVE}: may sign in; {@code REVOKED}: access revoked (only «Повернути доступ»); {@code DISABLED}: off. */
    public enum AccessStatus {
        ACTIVE,
        REVOKED,
        DISABLED;

        static AccessStatus of(Account account) {
            if (account.enabled()) {
                return ACTIVE;
            }
            return account.revoked() ? REVOKED : DISABLED;
        }
    }

    /**
     * One person with access: the account of {@code auth} and, for a teacher, the linked teacher of the catalog.
     *
     * @param username {@code null} for a grant by e-mail that has not chosen a username (Google or a password
     *                 link sets one)
     */
    public record AccessDto(UUID id, String username, String email, String displayName, List<String> roles,
                            AccessStatus status, boolean hasPassword, GoogleDto google, boolean demo,
                            Instant lastLoginAt, TeacherDto teacher) {

        static AccessDto of(Account account, Teacher teacher) {
            return new AccessDto(account.id(), account.username(), account.email(), account.displayName(),
                    account.roles().stream().sorted().toList(), AccessStatus.of(account), account.hasPassword(),
                    new GoogleDto(account.googleLinked()), account.demo(), account.lastLoginAt(),
                    teacher == null ? null : TeacherDto.of(teacher));
        }
    }

    public record GoogleDto(boolean linked) {
    }

    public record TeacherDto(UUID id, String shortName, String displayName) {

        static TeacherDto of(Teacher teacher) {
            return new TeacherDto(teacher.getId(), teacher.getShortName(), TeacherNames.fullName(teacher.getLastName(),
                    teacher.getFirstName(), teacher.getMiddleName(), teacher.getShortName()));
        }
    }

    /**
     * {@code POST /api/admin/access}: a grant by e-mail without a password. {@code TEACHER} and {@code teacherId}
     * come together; {@code displayName} defaults to the teacher's full name.
     */
    public record GrantRequest(
            @NotBlank(message = "Вкажіть e-mail") @Email(message = "Перевірте адресу e-mail")
            @Size(max = 254, message = "E-mail задовгий") String email,
            @Size(max = 200, message = "Ім'я задовге") String displayName,
            @NotEmpty(message = "Виберіть роль")
            Set<@Pattern(regexp = "ADMIN|TEACHER", message = "Невідома роль") String> roles,
            UUID teacherId) {
    }

    /** {@code PATCH /api/admin/access/{accountId}}: absent fields stay as they are. */
    public record UpdateRequest(
            Set<@Pattern(regexp = "ADMIN|TEACHER", message = "Невідома роль") String> roles,
            @Size(min = 1, max = 200, message = "Ім'я — від 1 до 200 символів") String displayName,
            UUID teacherId) {
    }
}
