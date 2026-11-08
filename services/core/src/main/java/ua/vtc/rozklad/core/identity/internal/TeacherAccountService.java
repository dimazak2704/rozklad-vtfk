package ua.vtc.rozklad.core.identity.internal;

import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.identity.InviteRepository;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.Account;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.IdentityProviderException;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.security.Roles;

/**
 * Links a teacher of the catalog to an account that already exists in Keycloak (an account made in
 * the admin console, the demo accounts of the realm import) and removes that link. New accounts are
 * created through invites ({@link InviteService}); this service never creates or deletes users.
 */
@Service
@Transactional
public class TeacherAccountService {

    private static final Logger log = LoggerFactory.getLogger(TeacherAccountService.class);

    private final TeacherRepository teachers;
    private final InviteRepository invites;
    private final IdentityProvider identityProvider;
    private final Clock clock;

    public TeacherAccountService(TeacherRepository teachers, InviteRepository invites, IdentityProvider identityProvider,
                                 Clock clock) {
        this.teachers = teachers;
        this.invites = invites;
        this.identityProvider = identityProvider;
        this.clock = clock;
    }

    /** The linked account; {@code linked=false} without one, {@code username=null} when Keycloak lost it. */
    @Transactional(readOnly = true)
    public TeacherAccountDto get(UUID teacherId) {
        Teacher teacher = teacher(teacherId);
        if (teacher.getKeycloakUserId() == null) {
            return TeacherAccountDto.unlinked(teacher);
        }
        return TeacherAccountDto.of(teacher, lookup(() -> identityProvider.findUser(teacher.getKeycloakUserId())));
    }

    /**
     * @throws NotFoundException     unknown teacher (404)
     * @throws BusinessRuleException no such account or a disabled one (422)
     * @throws ConflictException     the account belongs to another teacher, or the teacher has another account (409)
     */
    public TeacherAccountDto link(UUID teacherId, String rawUsername, String actor) {
        Teacher teacher = teacher(teacherId);
        String username = rawUsername.strip().toLowerCase(Locale.ROOT);
        Account account = lookup(() -> identityProvider.findUserByUsername(username))
                .orElseThrow(() -> new BusinessRuleException("ACCOUNT_NOT_FOUND",
                        "Обліковий запис «%s» не знайдено на сервері входу.".formatted(username)));
        if (!account.enabled()) {
            throw new BusinessRuleException("ACCOUNT_DISABLED", ("Обліковий запис «%s» вимкнено. Надішліть викладачеві "
                    + "запрошення, щоб відновити доступ.").formatted(account.username()));
        }
        if (account.id().equals(teacher.getKeycloakUserId())) {
            return TeacherAccountDto.of(teacher, Optional.of(account));
        }
        teachers.findByKeycloakUserId(account.id()).ifPresent(owner -> {
            throw new ConflictException("Обліковий запис «%s» уже прив'язано до викладача %s."
                    .formatted(account.username(), owner.getShortName()));
        });
        if (teacher.getKeycloakUserId() != null) {
            // зв'язок з обліковим записом, який видалили в Keycloak, можна замінити без відв'язування
            Optional<Account> current = lookup(() -> identityProvider.findUser(teacher.getKeycloakUserId()));
            if (current.isPresent()) {
                throw new ConflictException("Викладач %s уже має обліковий запис «%s». Спершу відв'яжіть його."
                        .formatted(teacher.getShortName(), current.get().username()));
            }
        }
        lookup(() -> {
            identityProvider.grantRealmRole(account.id(), Roles.TEACHER);
            return null;
        });
        teacher.setKeycloakUserId(account.id());
        // відкрите запрошення тепер вело б до іншого імені користувача — відкликаємо його
        invites.findAllByTeacherIdAndUsedAtIsNullAndRevokedAtIsNull(teacher.getId())
                .forEach(invite -> invite.revoke(clock.instant()));
        log.info("Teacher {} linked to account {} by {}", teacher.getId(), account.id(), actor);
        return TeacherAccountDto.of(teacher, Optional.of(account));
    }

    /** Idempotent: a teacher without an account stays as is. The Keycloak user itself is kept. */
    public void unlink(UUID teacherId, String actor) {
        Teacher teacher = teacher(teacherId);
        if (teacher.getKeycloakUserId() != null) {
            log.info("Teacher {} unlinked from account {} by {}", teacher.getId(), teacher.getKeycloakUserId(), actor);
            teacher.setKeycloakUserId(null);
        }
    }

    private Teacher teacher(UUID teacherId) {
        return teachers.findById(teacherId).orElseThrow(() -> NotFoundException.of("Викладач", teacherId));
    }

    private static <T> T lookup(Supplier<T> call) {
        try {
            return call.get();
        } catch (IdentityProviderException unavailable) {
            log.error("Identity provider failed while linking a teacher account", unavailable);
            throw IdentityProblems.identityLookupUnavailable(unavailable);
        }
    }

    /** {@code GET/PUT /api/admin/teachers/{id}/account}. */
    public record TeacherAccountDto(UUID teacherId, String teacherName, boolean linked, UUID userId, String username,
                                    Boolean enabled) {

        static TeacherAccountDto unlinked(Teacher teacher) {
            return new TeacherAccountDto(teacher.getId(), teacher.getShortName(), false, null, null, null);
        }

        static TeacherAccountDto of(Teacher teacher, Optional<Account> account) {
            return new TeacherAccountDto(teacher.getId(), teacher.getShortName(), true, teacher.getKeycloakUserId(),
                    account.map(Account::username).orElse(null), account.map(Account::enabled).orElse(null));
        }
    }
}
