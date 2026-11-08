package ua.vtc.rozklad.core.identity.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.UriComponentsBuilder;
import ua.vtc.rozklad.core.catalog.CatalogQueries;
import ua.vtc.rozklad.core.catalog.Teacher;
import ua.vtc.rozklad.core.catalog.TeacherNames;
import ua.vtc.rozklad.core.catalog.TeacherRef;
import ua.vtc.rozklad.core.catalog.TeacherRepository;
import ua.vtc.rozklad.core.identity.Invite;
import ua.vtc.rozklad.core.identity.InviteRepository;
import ua.vtc.rozklad.core.identity.InviteStatus;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.Account;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.IdentityProviderException;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.NewAccount;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.PasswordRejectedException;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.UsernameTakenException;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.AcceptedInviteDto;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.InviteCreatedDto;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.InviteDto;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.InviteInfoDto;
import ua.vtc.rozklad.core.shared.AppProperties;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.FieldErrors;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.security.Roles;

/**
 * Registration by invite (ARCHITECTURE §6). The admin creates an invite for a teacher and gets a
 * one-time link; the teacher opens it, picks a username and password, and the account is created
 * in Keycloak with the realm role TEACHER and linked to the teacher. A new invite revokes the
 * teacher's older ones, and using one revokes the rest, so only the latest link ever works. A
 * teacher who already has an account gets access back (enabled, new password) under the same
 * username.
 */
@Service
public class InviteService {

    private static final Logger log = LoggerFactory.getLogger(InviteService.class);
    static final String NOT_FOUND = "Запрошення не знайдено. Перевірте посилання або попросіть нове.";

    private final InviteRepository invites;
    private final TeacherRepository teachers;
    private final CatalogQueries catalog;
    private final IdentityProvider identityProvider;
    private final IdentityProperties properties;
    private final AppProperties appProperties;
    private final Clock clock;

    public InviteService(InviteRepository invites, TeacherRepository teachers, CatalogQueries catalog,
                         IdentityProvider identityProvider, IdentityProperties properties, AppProperties appProperties,
                         Clock clock) {
        this.invites = invites;
        this.teachers = teachers;
        this.catalog = catalog;
        this.identityProvider = identityProvider;
        this.properties = properties;
        this.appProperties = appProperties;
        this.clock = clock;
    }

    @Transactional
    public InviteCreatedDto create(UUID teacherId, String actor) {
        Teacher teacher = teachers.findById(teacherId)
                .orElseThrow(() -> FieldValidationException.of("teacherId", "Викладача не знайдено."));
        if (!teacher.isActive()) {
            throw new BusinessRuleException("TEACHER_INACTIVE",
                    "Викладач «%s» неактивний: спершу активуйте його в каталозі.".formatted(teacher.getShortName()));
        }
        Instant now = clock.instant();
        invites.findAllByTeacherIdAndUsedAtIsNullAndRevokedAtIsNull(teacherId).forEach(old -> old.revoke(now));
        String token = InviteTokens.newToken();
        Invite invite = invites.save(new Invite(teacherId, InviteTokens.hash(token), now.plus(properties.inviteTtl()),
                actor));
        return new InviteCreatedDto(invite.getId(), token, link(token), invite.getExpiresAt(), TeacherRef.of(teacher));
    }

    /** Newest first; optionally of one teacher. */
    @Transactional(readOnly = true)
    public List<InviteDto> list(UUID teacherId) {
        List<Invite> found = teacherId == null
                ? invites.findAllByOrderByCreatedAtDesc()
                : invites.findAllByTeacherIdOrderByCreatedAtDesc(teacherId);
        Map<UUID, TeacherRef> refs = catalog.teacherRefs(found.stream().map(Invite::getTeacherId)
                .collect(Collectors.toSet()));
        Instant now = clock.instant();
        return found.stream()
                .map(invite -> new InviteDto(invite.getId(), refs.get(invite.getTeacherId()), invite.status(now),
                        invite.getExpiresAt(), invite.getUsedAt(), invite.getRevokedAt(), invite.getCreatedAt(),
                        invite.getCreatedBy()))
                .toList();
    }

    /** What the registration page shows before the form. */
    @Transactional(readOnly = true)
    public InviteInfoDto inspect(String token) {
        Invite invite = findByToken(token);
        Teacher teacher = teachers.findById(invite.getTeacherId()).orElseThrow(() -> new NotFoundException(NOT_FOUND));
        InviteStatus status = invite.status(clock.instant());
        return new InviteInfoDto(status == InviteStatus.ACTIVE, status, displayName(teacher), invite.getExpiresAt());
    }

    /**
     * @throws NotFoundException      unknown token (404)
     * @throws org.springframework.web.ErrorResponseException 410 used / expired / revoked, 503 Keycloak down
     * @throws ConflictException      username taken, or another username than the teacher's account (409)
     */
    @Transactional
    public AcceptedInviteDto accept(String token, String rawUsername, String password) {
        if (!InviteTokens.hasValidFormat(token)) {
            throw new NotFoundException(NOT_FOUND);
        }
        Invite invite = invites.lockByTokenHash(InviteTokens.hash(token)).orElseThrow(() -> new NotFoundException(NOT_FOUND));
        Instant now = clock.instant();
        InviteStatus status = invite.status(now);
        if (status != InviteStatus.ACTIVE) {
            throw IdentityProblems.inviteGone(status);
        }
        String username = rawUsername.strip().toLowerCase(Locale.ROOT);
        if (password.equalsIgnoreCase(username)) {
            new FieldErrors().add("password", "Пароль не може збігатися з ім'ям користувача.").throwIfAny();
        }
        Teacher teacher = teachers.findById(invite.getTeacherId()).orElseThrow(() -> new NotFoundException(NOT_FOUND));
        UUID userId;
        try {
            userId = existingAccount(teacher)
                    .map(account -> restore(account, username, password))
                    .orElseGet(() -> createAccount(teacher, username, password));
        } catch (UsernameTakenException taken) {
            throw new ConflictException("Ім'я користувача «%s» уже зайняте. Виберіть інше.".formatted(username));
        } catch (PasswordRejectedException rejected) {
            log.info("Keycloak rejected the password of a new account: {}", rejected.getMessage());
            throw FieldValidationException.of("password", "Пароль не відповідає правилам безпеки сервера входу.");
        } catch (IdentityProviderException unavailable) {
            log.error("Identity provider failed while accepting invite {}", invite.getId(), unavailable);
            throw IdentityProblems.identityUnavailable(unavailable);
        }
        teacher.setKeycloakUserId(userId);
        invite.markUsed(now);
        invites.findAllByTeacherIdAndUsedAtIsNullAndRevokedAtIsNull(teacher.getId()).stream()
                .filter(other -> !other.getId().equals(invite.getId()))
                .forEach(other -> other.revoke(now));
        log.info("Invite {} accepted: teacher {} linked to account {}", invite.getId(), teacher.getId(), userId);
        return new AcceptedInviteDto(username);
    }

    private Invite findByToken(String token) {
        if (!InviteTokens.hasValidFormat(token)) {
            throw new NotFoundException(NOT_FOUND);
        }
        return invites.findByTokenHash(InviteTokens.hash(token)).orElseThrow(() -> new NotFoundException(NOT_FOUND));
    }

    /** The linked account if it still exists in Keycloak (it may have been deleted there). */
    private Optional<Account> existingAccount(Teacher teacher) {
        return Optional.ofNullable(teacher.getKeycloakUserId()).flatMap(identityProvider::findUser);
    }

    private UUID restore(Account account, String username, String password) {
        if (!account.username().equalsIgnoreCase(username)) {
            throw new ConflictException(("Для викладача вже є обліковий запис «%s». Вкажіть це ім'я користувача, "
                    + "щоб відновити доступ.").formatted(account.username()));
        }
        identityProvider.restoreAccess(account.id(), password);
        identityProvider.grantRealmRole(account.id(), Roles.TEACHER);
        return account.id();
    }

    private UUID createAccount(Teacher teacher, String username, String password) {
        UUID userId = identityProvider.createUser(new NewAccount(username, password, teacher.getFirstName(),
                teacher.getLastName()));
        // якщо транзакція не закомітиться (зв'язок з викладачем не збережено), обліковий запис не має лишитися
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(userId);
                }
            }
        });
        identityProvider.grantRealmRole(userId, Roles.TEACHER);
        return userId;
    }

    private void deleteQuietly(UUID userId) {
        try {
            identityProvider.deleteUser(userId);
        } catch (RuntimeException failed) {
            log.error("Orphan Keycloak user {} after a failed invite; delete it in the admin console", userId, failed);
        }
    }

    private String link(String token) {
        return UriComponentsBuilder.fromUri(appProperties.publicWebUrl())
                .path("/invite/{token}")
                .buildAndExpand(token)
                .toUriString();
    }

    private static String displayName(Teacher teacher) {
        return TeacherNames.fullName(teacher.getLastName(), teacher.getFirstName(), teacher.getMiddleName(),
                teacher.getShortName());
    }
}
