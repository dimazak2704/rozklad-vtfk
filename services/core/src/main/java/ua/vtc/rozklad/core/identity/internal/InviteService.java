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
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.AccountNotFoundException;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.AccountRevokedException;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.IdentityProviderException;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.NewAccount;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.PasswordRejectedException;
import ua.vtc.rozklad.core.identity.internal.IdentityProvider.UsernameTakenException;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.AcceptedInviteDto;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.InviteCreatedDto;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.InviteDto;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.InviteInfoDto;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.InviteKind;
import ua.vtc.rozklad.core.identity.internal.InviteDtos.PasswordLinkDto;
import ua.vtc.rozklad.core.shared.AppProperties;
import ua.vtc.rozklad.core.shared.error.BusinessRuleException;
import ua.vtc.rozklad.core.shared.error.ConflictException;
import ua.vtc.rozklad.core.shared.error.FieldErrors;
import ua.vtc.rozklad.core.shared.error.FieldValidationException;
import ua.vtc.rozklad.core.shared.error.NotFoundException;
import ua.vtc.rozklad.core.shared.security.Roles;
import ua.vtc.rozklad.core.shared.time.StandClock;

/**
 * Registration by invite (ARCHITECTURE §6). The admin creates an invite for a teacher and gets a
 * one-time link; the teacher opens it, picks a username and password, and the account is created
 * in the sign-in service {@code auth} with the role TEACHER and linked to the teacher. A new invite
 * revokes the teacher's older ones, and using one revokes the rest, so only the latest link ever
 * works. A teacher who already has an account gets access back (enabled, new password) under the same
 * username; an account granted by e-mail without a username gets the chosen one. A revoked account
 * (DESIGN D11) is never re-enabled by an invite: the invite is revoked and the answer is 410.
 */
@Service
public class InviteService {

    private static final Logger log = LoggerFactory.getLogger(InviteService.class);
    static final String NOT_FOUND = "Запрошення не знайдено. Перевірте посилання або попросіть нове.";
    /** SPA pages of the links: registration and a new password. */
    static final String INVITE_PATH = "/invite/{token}";
    static final String PASSWORD_PATH = "/password/{token}";

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
        this.clock = StandClock.real(clock); // expiry follows real time, not the stand time
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
                ? invites.findAllByTeacherIdIsNotNullOrderByCreatedAtDesc()
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

    /** What the registration (or password) page shows before the form. */
    @Transactional(readOnly = true)
    public InviteInfoDto inspect(String token) {
        Invite invite = findByToken(token);
        InviteStatus status = invite.status(clock.instant());
        if (invite.isPasswordLink()) {
            Account account = linkedAccount(invite);
            if (account.revoked() && status == InviteStatus.ACTIVE) {
                status = InviteStatus.REVOKED;
            }
            return new InviteInfoDto(status == InviteStatus.ACTIVE, status, account.displayName(),
                    invite.getExpiresAt(), InviteKind.PASSWORD, account.username());
        }
        Teacher teacher = teachers.findById(invite.getTeacherId()).orElseThrow(() -> new NotFoundException(NOT_FOUND));
        return new InviteInfoDto(status == InviteStatus.ACTIVE, status, displayName(teacher), invite.getExpiresAt(),
                InviteKind.INVITE, null);
    }

    /**
     * A password link for an account (DESIGN §4.6, «Посилання для пароля» in «Доступи»): older open links of the
     * account are revoked, so only the latest one works. A revoked account gets none.
     *
     * @throws NotFoundException no such account in {@code auth}
     * @throws org.springframework.web.ErrorResponseException 409 {@code ACCOUNT_REVOKED}, 503 sign-in service down
     */
    @Transactional
    public PasswordLinkDto createPasswordLink(UUID accountId, String actor) {
        Account account;
        try {
            account = identityProvider.findUser(accountId)
                    .orElseThrow(() -> NotFoundException.of("Обліковий запис", accountId));
        } catch (IdentityProviderException unavailable) {
            throw IdentityProblems.identityLookupUnavailable(unavailable);
        }
        if (account.revoked()) {
            throw IdentityProblems.accessConflict("ACCOUNT_REVOKED", "Спершу поверніть доступ.");
        }
        Instant now = clock.instant();
        revokePasswordLinks(accountId, now);
        String token = InviteTokens.newToken();
        Invite link = invites.save(Invite.forAccount(accountId, InviteTokens.hash(token),
                now.plus(properties.inviteTtl()), actor));
        log.info("Password link {} created for account {} by {}", link.getId(), accountId, actor);
        return new PasswordLinkDto(link(PASSWORD_PATH, token), link.getExpiresAt());
    }

    /** Revokes the open password links of an account (a newer link, revoked access). */
    void revokePasswordLinks(UUID accountId, Instant now) {
        invites.findAllByAccountIdAndUsedAtIsNullAndRevokedAtIsNull(accountId).forEach(old -> old.revoke(now));
    }

    /** Revokes the open invites of a teacher (revoked access). */
    void revokeTeacherInvites(UUID teacherId, Instant now) {
        invites.findAllByTeacherIdAndUsedAtIsNullAndRevokedAtIsNull(teacherId).forEach(old -> old.revoke(now));
    }

    /**
     * @throws NotFoundException      unknown token (404)
     * @throws org.springframework.web.ErrorResponseException 410 used / expired / revoked invite or revoked
     *                                account, 503 sign-in service down
     * @throws ConflictException      username taken, or another username than the teacher's account (409)
     */
    @Transactional(noRollbackFor = IdentityProblems.AccessRevokedException.class)
    public AcceptedInviteDto accept(String token, String rawUsername, String password) {
        if (!InviteTokens.hasValidFormat(token)) {
            throw new NotFoundException(NOT_FOUND);
        }
        Invite invite = invites.lockByTokenHash(InviteTokens.hash(token)).orElseThrow(() -> new NotFoundException(NOT_FOUND));
        Instant now = clock.instant();
        InviteStatus status = invite.status(now);
        if (status != InviteStatus.ACTIVE) {
            if (status == InviteStatus.REVOKED && targetRevoked(invite)) {
                // доступ відкликали (D11): людина має знати саме це, а не «надіслали новіше»
                throw IdentityProblems.accessRevoked();
            }
            throw IdentityProblems.inviteGone(status);
        }
        String username = rawUsername.strip().toLowerCase(Locale.ROOT);
        if (password.equalsIgnoreCase(username)) {
            new FieldErrors().add("password", "Пароль не може збігатися з ім'ям користувача.").throwIfAny();
        }
        if (invite.isPasswordLink()) {
            return acceptPasswordLink(invite, username, password, now);
        }
        Teacher teacher = teachers.findById(invite.getTeacherId()).orElseThrow(() -> new NotFoundException(NOT_FOUND));
        UUID userId;
        try {
            Optional<Account> existing = existingAccount(teacher);
            if (existing.isPresent() && existing.get().revoked()) {
                throw accessRevoked(invite, now);
            }
            userId = existing
                    .map(account -> restore(account, username, password))
                    .orElseGet(() -> createAccount(teacher, username, password));
        } catch (AccountRevokedException revoked) {
            // відкликали між перевіркою і викликом: auth не вмикає такий запис (D11)
            throw accessRevoked(invite, now);
        } catch (UsernameTakenException taken) {
            throw new ConflictException("Ім'я користувача «%s» уже зайняте. Виберіть інше.".formatted(username));
        } catch (PasswordRejectedException rejected) {
            log.info("auth rejected the password of invite {}: {}", invite.getId(), rejected.reason());
            throw FieldValidationException.of("password", passwordRejected(rejected.reason()));
        } catch (AccountNotFoundException vanished) {
            log.error("Account of teacher {} vanished while accepting invite {}", teacher.getId(), invite.getId(),
                    vanished);
            throw IdentityProblems.identityUnavailable(vanished);
        } catch (IdentityProviderException unavailable) {
            log.error("Identity provider failed while accepting invite {}", invite.getId(), unavailable);
            throw IdentityProblems.identityUnavailable(unavailable);
        }
        teacher.setAccountId(userId);
        invite.markUsed(now);
        invites.findAllByTeacherIdAndUsedAtIsNullAndRevokedAtIsNull(teacher.getId()).stream()
                .filter(other -> !other.getId().equals(invite.getId()))
                .forEach(other -> other.revoke(now));
        log.info("Invite {} accepted: teacher {} linked to account {}", invite.getId(), teacher.getId(), userId);
        return new AcceptedInviteDto(username);
    }

    /**
     * A password link: the account keeps its username (or gets the chosen one if it has none), gets the new
     * password and is enabled; a revoked account is not (410, D11).
     */
    private AcceptedInviteDto acceptPasswordLink(Invite invite, String username, String password, Instant now) {
        UUID accountId = invite.getAccountId();
        try {
            Account account = linkedAccount(invite);
            if (account.revoked()) {
                throw accessRevoked(invite, now);
            }
            if (account.username() == null) {
                identityProvider.setUsername(accountId, username);
            } else if (!account.username().equalsIgnoreCase(username)) {
                throw new ConflictException("Ваше ім'я користувача — «%s». Вкажіть саме його."
                        .formatted(account.username()));
            }
            identityProvider.restoreAccess(accountId, password);
        } catch (AccountRevokedException revoked) {
            throw accessRevoked(invite, now);
        } catch (UsernameTakenException taken) {
            throw new ConflictException("Ім'я користувача «%s» уже зайняте. Виберіть інше.".formatted(username));
        } catch (PasswordRejectedException rejected) {
            log.info("auth rejected the password of link {}: {}", invite.getId(), rejected.reason());
            throw FieldValidationException.of("password", passwordRejected(rejected.reason()));
        } catch (IdentityProviderException | AccountNotFoundException unavailable) {
            log.error("Identity provider failed while accepting password link {}", invite.getId(), unavailable);
            throw IdentityProblems.identityUnavailable(unavailable);
        }
        invite.markUsed(now);
        invites.findAllByAccountIdAndUsedAtIsNullAndRevokedAtIsNull(accountId).stream()
                .filter(other -> !other.getId().equals(invite.getId()))
                .forEach(other -> other.revoke(now));
        log.info("Password link {} used: account {} has a new password", invite.getId(), accountId);
        return new AcceptedInviteDto(username);
    }

    /**
     * Whether the account the link leads to (the teacher's, or the password link's) is revoked. An unreachable
     * {@code auth} counts as "not known to be revoked": the answer is then the plain «invite revoked».
     */
    private boolean targetRevoked(Invite invite) {
        try {
            UUID accountId = invite.isPasswordLink() ? invite.getAccountId()
                    : teachers.findById(invite.getTeacherId()).map(Teacher::getAccountId).orElse(null);
            return accountId != null && identityProvider.findUser(accountId).map(Account::revoked).orElse(false);
        } catch (IdentityProviderException unavailable) {
            log.warn("Could not check the account of revoked invite {}", invite.getId(), unavailable);
            return false;
        }
    }

    /** The account of a password link; 404 when {@code auth} no longer has it, 503 when it cannot be asked. */
    private Account linkedAccount(Invite invite) {
        try {
            return identityProvider.findUser(invite.getAccountId()).orElseThrow(() -> new NotFoundException(NOT_FOUND));
        } catch (IdentityProviderException unavailable) {
            throw IdentityProblems.identityLookupUnavailable(unavailable);
        }
    }

    private Invite findByToken(String token) {
        if (!InviteTokens.hasValidFormat(token)) {
            throw new NotFoundException(NOT_FOUND);
        }
        return invites.findByTokenHash(InviteTokens.hash(token)).orElseThrow(() -> new NotFoundException(NOT_FOUND));
    }

    /** The linked account if it still exists in {@code auth} (it may have been deleted there). */
    private Optional<Account> existingAccount(Teacher teacher) {
        return Optional.ofNullable(teacher.getAccountId()).flatMap(identityProvider::findUser);
    }

    /** 410 for a revoked account; the invite is revoked too and stays so (the transaction commits). */
    private static IdentityProblems.AccessRevokedException accessRevoked(Invite invite, Instant now) {
        invite.revoke(now);
        log.info("Invite {} refused: the linked account is revoked", invite.getId());
        return IdentityProblems.accessRevoked();
    }

    private UUID restore(Account account, String username, String password) {
        if (account.username() == null) {
            // доступ надали за e-mail у «Доступах»: викладач вибирає ім'я користувача тут
            identityProvider.setUsername(account.id(), username);
        } else if (!account.username().equalsIgnoreCase(username)) {
            throw new ConflictException(("Для викладача вже є обліковий запис «%s». Вкажіть це ім'я користувача, "
                    + "щоб відновити доступ.").formatted(account.username()));
        }
        identityProvider.restoreAccess(account.id(), password);
        identityProvider.grantRole(account.id(), Roles.TEACHER);
        return account.id();
    }

    private UUID createAccount(Teacher teacher, String username, String password) {
        NewAccount account = NewAccount.teacher(username, displayName(teacher), password);
        // якщо транзакція не закомітиться (зв'язок з викладачем не збережено), обліковий запис не має лишитися;
        // реєструємо до виклику: auth міг створити запис, навіть якщо відповідь не дійшла
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(account.id());
                }
            }
        });
        return identityProvider.createUser(account);
    }

    private void deleteQuietly(UUID userId) {
        try {
            identityProvider.deleteUser(userId);
        } catch (RuntimeException failed) {
            log.error("Orphan account {} in auth after a failed invite; delete it by hand", userId, failed);
        }
    }

    /** Ukrainian text of a {@code password-rejected} reason of {@code auth}. */
    static String passwordRejected(String reason) {
        return switch (reason == null ? "" : reason) {
            case "too-short" -> "Пароль має містити щонайменше %d символів.".formatted(InviteDtos.MIN_PASSWORD);
            case "too-long" -> "Пароль задовгий: щонайбільше %d символів, а кирилицею — %d."
                    .formatted(InviteDtos.MAX_PASSWORD, InviteDtos.MAX_PASSWORD_CYRILLIC);
            case "same-as-username" -> "Пароль не може збігатися з ім'ям користувача.";
            case "same-as-email" -> "Пароль не може збігатися з адресою e-mail.";
            case "too-common" -> "Цей пароль надто поширений. Виберіть інший.";
            default -> "Пароль не відповідає правилам безпеки сервера входу.";
        };
    }

    private String link(String token) {
        return link(INVITE_PATH, token);
    }

    private String link(String path, String token) {
        return UriComponentsBuilder.fromUri(appProperties.publicWebUrl())
                .path(path)
                .buildAndExpand(token)
                .toUriString();
    }

    private static String displayName(Teacher teacher) {
        return TeacherNames.fullName(teacher.getLastName(), teacher.getFirstName(), teacher.getMiddleName(),
                teacher.getShortName());
    }
}
