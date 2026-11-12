package ua.vtc.rozklad.core.identity.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherNames;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.identity.internal.AccessDtos.AccessDto;
import ua.vtc.rozklad.core.identity.internal.AccessDtos.AccessStatus;
import ua.vtc.rozklad.core.identity.internal.AccessDtos.GrantRequest;
import ua.vtc.rozklad.core.identity.internal.AccessDtos.UpdateRequest;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.Account;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.AccountNotFoundException;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.EmailTakenException;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.IdentityProviderException;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.LastAdminException;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.NewAccount;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.PasswordLinkDto;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.security.CurrentUser;
import ua.vtc.rozklad.core.shared.security.Roles;

/**
 * Access grants, the page «Доступи» (DESIGN §4.7). The data lives in {@code auth} (an enabled account is
 * what Google sign-in accepts); core owns the rules: who may get which role, that a TEACHER is an active
 * catalog teacher linked to nobody else, that nobody revokes themselves or the last admin, and that a revoked
 * access (D11) comes back only through {@link #restore}. Revoking also revokes the open invites of the teacher
 * and the open password links of the account, in the same transaction, before {@code auth} is asked; if
 * {@code auth} fails the transaction rolls back and the admin retries.
 */
@Service
public class AccessService {

    private static final Logger log = LoggerFactory.getLogger(AccessService.class);
    static final String LAST_ADMIN = "Має залишитися хоча б один адміністратор, який може увійти.";
    static final String EMAIL_TAKEN = "Ця адреса вже має доступ.";

    private final IdentityProvider identityProvider;
    private final TeacherRepository teachers;
    private final InviteService invites;
    private final AccountLinkLocks locks;
    private final Clock clock;

    AccessService(IdentityProvider identityProvider, TeacherRepository teachers, InviteService invites,
                  AccountLinkLocks locks, Clock clock) {
        this.identityProvider = identityProvider;
        this.teachers = teachers;
        this.invites = invites;
        this.locks = locks;
        this.clock = clock;
    }

    /** Everyone with an account, filtered by text (name, e-mail, username, teacher), role and status. */
    @Transactional(readOnly = true)
    public List<AccessDto> list(String query, String role, AccessStatus status) {
        List<Account> accounts = call(identityProvider::listUsers);
        Map<UUID, Teacher> byAccount = teachers.findAllByAccountIdIn(accounts.stream().map(Account::id).toList())
                .stream().collect(Collectors.toMap(Teacher::getAccountId, Function.identity(), (a, b) -> a));
        String needle = StringUtils.hasText(query) ? query.strip().toLowerCase(Locale.ROOT) : null;
        return accounts.stream()
                .map(account -> AccessDto.of(account, byAccount.get(account.id())))
                .filter(access -> role == null || access.roles().contains(role))
                .filter(access -> status == null || access.status() == status)
                .filter(access -> needle == null || matches(access, needle))
                .sorted(Comparator.comparing((AccessDto access) -> access.displayName().toLowerCase(Locale.ROOT))
                        .thenComparing(AccessDto::id))
                .toList();
    }

    /**
     * Grants access by e-mail: an account without a password (Google, or a password link later). For TEACHER the
     * teacher is linked in the same transaction; if the transaction fails the new account is deleted again.
     */
    @Transactional
    public AccessDto grant(GrantRequest request, String actor) {
        Set<String> roles = Set.copyOf(request.roles());
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        Teacher teacher = teacherForRoles(roles, request.teacherId());
        String displayName = StringUtils.hasText(request.displayName()) ? request.displayName().strip()
                : teacher != null ? fullName(teacher) : null;
        if (displayName == null) {
            throw FieldValidationException.of("displayName", "Вкажіть ім'я.");
        }
        UUID id = UUID.randomUUID();
        if (teacher != null) {
            locks.lockAccountAndTeacher(id, teacher.getId());
            requireUnlinked(teacher);
        }
        // якщо зв'язок з викладачем не збережеться, обліковий запис не має лишитися (бази різні)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(id);
                }
            }
        });
        try {
            identityProvider.createUser(new NewAccount(id, null, email, displayName, roles, null));
        } catch (EmailTakenException taken) {
            throw IdentityProblems.accessConflict("EMAIL_TAKEN", EMAIL_TAKEN);
        } catch (IdentityProviderException unavailable) {
            throw IdentityProblems.identityChangeUnavailable(unavailable);
        }
        if (teacher != null) {
            teacher.setAccountId(id);
        }
        log.info("Access granted to account {} ({}) by {}", id, roles, actor);
        Account account = call(() -> identityProvider.findUser(id)).orElseThrow(() ->
                IdentityProblems.identityChangeUnavailable(new AccountNotFoundException(id)));
        return AccessDto.of(account, teacher);
    }

    /** Changes roles, the name and the linked teacher; removing TEACHER also removes the teacher link. */
    @Transactional
    public AccessDto update(UUID accountId, UpdateRequest request, String actor) {
        Account account = account(accountId);
        Optional<Teacher> linked = teachers.findByAccountId(accountId);
        Set<String> roles = request.roles() == null ? account.roles() : Set.copyOf(request.roles());
        if (roles.isEmpty()) {
            throw FieldValidationException.of("roles", "Виберіть роль");
        }
        Teacher teacher = null;
        if (roles.contains(Roles.TEACHER)) {
            UUID teacherId = request.teacherId() != null ? request.teacherId()
                    : linked.map(Teacher::getId).orElse(null);
            teacher = teacherForRoles(roles, teacherId);
        } else if (request.teacherId() != null) {
            throw new BusinessRuleException("TEACHER_ROLE_REQUIRED", "Викладача можна прив'язати лише з роллю «Викладач».");
        }
        if (teacher != null) {
            locks.lockAccountAndTeacher(accountId, teacher.getId());
            if (!accountId.equals(teacher.getAccountId())) {
                requireUnlinked(teacher);
            }
        }
        if (!roles.equals(account.roles())) {
            mutate(() -> identityProvider.setRoles(accountId, roles));
        }
        if (request.displayName() != null && !request.displayName().strip().equals(account.displayName())) {
            mutate(() -> identityProvider.setDisplayName(accountId, request.displayName().strip()));
        }
        Teacher target = teacher;
        linked.filter(current -> target == null || !current.getId().equals(target.getId()))
                .ifPresent(current -> current.setAccountId(null));
        if (teacher != null) {
            teacher.setAccountId(accountId);
        }
        log.info("Access of account {} changed by {}: roles {}", accountId, actor, roles);
        return AccessDto.of(account(accountId), teacher);
    }

    /**
     * Revokes the access (D11): open invites of the linked teacher and open password links of the account are
     * revoked here, then {@code auth} disables the account and ends its sessions. The teacher link stays.
     */
    @Transactional
    public AccessDto revoke(UUID accountId, CurrentUser admin) {
        if (accountId.equals(admin.id())) {
            throw new BusinessRuleException("SELF_REVOKE", "Не можна відкликати власний доступ.");
        }
        account(accountId);
        Instant now = clock.instant();
        Optional<Teacher> teacher = teachers.findByAccountId(accountId);
        teacher.ifPresent(linked -> invites.revokeTeacherInvites(linked.getId(), now));
        invites.revokePasswordLinks(accountId, now);
        mutate(() -> identityProvider.revoke(accountId));
        log.info("Access of account {} revoked by {}", accountId, admin.username());
        return AccessDto.of(account(accountId), teacher.orElse(null));
    }

    /** The only way to clear a revocation. */
    @Transactional
    public AccessDto restore(UUID accountId, String actor) {
        account(accountId);
        mutate(() -> identityProvider.restore(accountId));
        log.info("Access of account {} restored by {}", accountId, actor);
        return AccessDto.of(account(accountId), teachers.findByAccountId(accountId).orElse(null));
    }

    /** Removes the Google binding, e.g. after the Workspace account was created again with the same address. */
    @Transactional
    public void unbindGoogle(UUID accountId, String actor) {
        account(accountId);
        mutate(() -> identityProvider.unbindGoogle(accountId));
        log.info("Google binding of account {} removed by {}", accountId, actor);
    }

    @Transactional
    public PasswordLinkDto passwordLink(UUID accountId, String actor) {
        return invites.createPasswordLink(accountId, actor);
    }

    /** TEACHER and a teacher come together; the teacher must exist and be active. */
    private Teacher teacherForRoles(Set<String> roles, UUID teacherId) {
        boolean teacherRole = roles.contains(Roles.TEACHER);
        if (teacherRole && teacherId == null) {
            throw FieldValidationException.of("teacherId", "Для ролі «Викладач» виберіть викладача.");
        }
        if (!teacherRole && teacherId != null) {
            throw new BusinessRuleException("TEACHER_ROLE_REQUIRED", "Викладача можна прив'язати лише з роллю «Викладач».");
        }
        if (!teacherRole) {
            return null;
        }
        Teacher teacher = teachers.findById(teacherId)
                .orElseThrow(() -> FieldValidationException.of("teacherId", "Викладача не знайдено."));
        if (!teacher.isActive()) {
            throw new BusinessRuleException("TEACHER_INACTIVE",
                    "Викладач «%s» неактивний: спершу активуйте його в каталозі.".formatted(teacher.getShortName()));
        }
        return teacher;
    }

    private void requireUnlinked(Teacher teacher) {
        if (teacher.getAccountId() != null) {
            throw IdentityProblems.accessConflict("TEACHER_ALREADY_LINKED",
                    "Викладач %s уже має обліковий запис.".formatted(teacher.getShortName()));
        }
    }

    private Account account(UUID accountId) {
        return call(() -> identityProvider.findUser(accountId))
                .orElseThrow(() -> NotFoundException.of("Обліковий запис", accountId));
    }

    /** A change in {@code auth}: the last-admin rule is 409, an unreachable {@code auth} 503. */
    private static void mutate(Runnable change) {
        try {
            change.run();
        } catch (LastAdminException last) {
            throw IdentityProblems.accessConflict("LAST_ADMIN", LAST_ADMIN);
        } catch (AccountNotFoundException | IdentityProviderException unavailable) {
            throw IdentityProblems.identityChangeUnavailable(unavailable);
        }
    }

    private static <T> T call(Supplier<T> lookup) {
        try {
            return lookup.get();
        } catch (IdentityProviderException unavailable) {
            throw IdentityProblems.identityLookupUnavailable(unavailable);
        }
    }

    private void deleteQuietly(UUID id) {
        try {
            identityProvider.deleteUser(id);
        } catch (RuntimeException failed) {
            log.error("Orphan account {} in auth after a failed access grant; delete it by hand", id, failed);
        }
    }

    private static boolean matches(AccessDto access, String needle) {
        return contains(access.displayName(), needle) || contains(access.email(), needle)
                || contains(access.username(), needle)
                || access.teacher() != null && (contains(access.teacher().shortName(), needle)
                        || contains(access.teacher().displayName(), needle));
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String fullName(Teacher teacher) {
        return TeacherNames.fullName(teacher.getLastName(), teacher.getFirstName(), teacher.getMiddleName(),
                teacher.getShortName());
    }
}
