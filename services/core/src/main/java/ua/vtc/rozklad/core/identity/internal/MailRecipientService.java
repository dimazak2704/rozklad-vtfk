package ua.vtc.rozklad.core.identity.internal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.Account;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.IdentityProviderException;
import ua.vtc.rozklad.core.shared.security.Roles;

/**
 * {@code GET /api/internal/mail-recipients} for notification's e-mail channel: who an e-mail about a teacher, an
 * account or all admins would reach. Returns facts from {@code auth} (address, Google binding, state); whether an
 * address is trusted enough to receive letters is decided by notification. An unknown teacher or account, a
 * teacher without an account and an account {@code auth} lost all give an empty list (nobody to write to).
 */
@Service
@Transactional(readOnly = true)
public class MailRecipientService {

    private final IdentityProvider identityProvider;
    private final TeacherRepository teachers;

    public MailRecipientService(IdentityProvider identityProvider, TeacherRepository teachers) {
        this.identityProvider = identityProvider;
        this.teachers = teachers;
    }

    /** The account linked to the teacher, if any. */
    public List<MailRecipientDto> forTeacher(UUID teacherId) {
        Optional<UUID> accountId = teachers.findById(teacherId).map(Teacher::getAccountId);
        if (accountId.isEmpty()) {
            return List.of();
        }
        return call(() -> identityProvider.findUser(accountId.get())).stream()
                .map(account -> MailRecipientDto.of(account, teacherId))
                .toList();
    }

    /** The account itself, with its teacher when one is linked. */
    public List<MailRecipientDto> forAccount(UUID accountId) {
        return call(() -> identityProvider.findUser(accountId)).stream()
                .map(account -> MailRecipientDto.of(account,
                        teachers.findByAccountId(accountId).map(Teacher::getId).orElse(null)))
                .toList();
    }

    /** Every account with the role ADMIN, disabled ones included (the caller filters). */
    public List<MailRecipientDto> admins() {
        List<Account> admins = call(identityProvider::listUsers).stream()
                .filter(account -> account.roles().contains(Roles.ADMIN))
                .toList();
        Map<UUID, UUID> teacherByAccount = admins.isEmpty() ? Map.of()
                : teachers.findAllByAccountIdIn(admins.stream().map(Account::id).toList()).stream()
                        .collect(Collectors.toMap(Teacher::getAccountId, Teacher::getId, (a, b) -> a));
        return admins.stream()
                .map(account -> MailRecipientDto.of(account, teacherByAccount.get(account.id())))
                .toList();
    }

    private static <T> T call(Supplier<T> lookup) {
        try {
            return lookup.get();
        } catch (IdentityProviderException unavailable) {
            throw IdentityProblems.identityLookupUnavailable(unavailable);
        }
    }

    /**
     * A person who may receive e-mail. {@code email} is the account's sign-in address in {@code auth} (lower case,
     * {@code null} for accounts created by invite); {@code googleLinked} means Google confirmed that address.
     */
    public record MailRecipientDto(UUID accountId, UUID teacherId, String displayName, String username, String email,
                                   boolean googleLinked, List<String> roles, boolean enabled, boolean revoked,
                                   boolean demo) {

        static MailRecipientDto of(Account account, UUID teacherId) {
            return new MailRecipientDto(account.id(), teacherId, account.displayName(), account.username(),
                    account.email(), account.googleLinked(), account.roles().stream().sorted().toList(),
                    account.enabled(), account.revoked(), account.demo());
        }
    }
}
