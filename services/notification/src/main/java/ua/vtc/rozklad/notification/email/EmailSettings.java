package ua.vtc.rozklad.notification.email;

import java.time.Clock;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import ua.vtc.rozklad.notification.config.MailChannelProperties;
import ua.vtc.rozklad.notification.config.StandClock;
import ua.vtc.rozklad.notification.core.CoreDtos.MailRecipient;
import ua.vtc.rozklad.notification.email.MailModels.TestMail;
import ua.vtc.rozklad.notification.web.BusinessRuleException;
import ua.vtc.rozklad.notification.web.FieldValidationException;
import ua.vtc.rozklad.notification.web.ServiceUnavailableException;
import ua.vtc.rozklad.notification.web.TooManyRequestsException;

/**
 * {@code /api/notify/email/me}: the caller's own e-mail settings, always taken from his token (no account id is
 * accepted from the request). Teacher kinds are offered to an account linked to a teacher, dispatcher kinds to
 * the role ADMIN. The address is read fresh from core: the person sees his own address unmasked.
 */
@Service
public class EmailSettings {

    static final String ADMIN = "ADMIN";
    static final String TEACHER = "TEACHER";

    private final EmailRecipients recipients;
    private final EmailPreferences preferences;
    private final EmailNotices notices;
    private final EmailRateGuard guard;
    private final MailChannel channel;
    private final MailChannelProperties properties;
    private final Clock clock;

    public EmailSettings(EmailRecipients recipients, EmailPreferences preferences, EmailNotices notices,
                         EmailRateGuard guard, MailChannel channel, MailChannelProperties properties, Clock clock) {
        this.recipients = recipients;
        this.preferences = preferences;
        this.notices = notices;
        this.guard = guard;
        this.channel = channel;
        this.properties = properties;
        this.clock = StandClock.real(clock);
    }

    public View of(UUID accountId, Set<String> tokenRoles) {
        Optional<MailRecipient> me = recipients.account(accountId);
        return view(accountId, me, tokenRoles, preferences.of(accountId));
    }

    /**
     * Saves the master switch and the listed kinds.
     *
     * @throws FieldValidationException {@code kinds.X} for an unknown kind
     * @throws BusinessRuleException    {@code email-kind-not-allowed} for a kind of another audience or one that
     *                                  cannot be switched
     */
    public View update(UUID accountId, Set<String> tokenRoles, boolean enabled, Map<String, Boolean> kinds,
                       Long expectedVersion) {
        Map<EmailKind, Boolean> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : (kinds == null ? Map.<String, Boolean>of() : kinds).entrySet()) {
            EmailKind kind = EmailKind.parse(entry.getKey())
                    .orElseThrow(() -> new FieldValidationException("kinds." + entry.getKey(), "Невідомий вид листа."));
            if (entry.getValue() == null) {
                throw new FieldValidationException("kinds." + entry.getKey(), "Вкажіть true або false.");
            }
            parsed.put(kind, entry.getValue());
        }
        Optional<MailRecipient> me = recipients.account(accountId);
        Set<EmailAudience> audiences = audiences(me, tokenRoles);
        for (EmailKind kind : parsed.keySet()) {
            if (!kind.listed() || !kind.switchable() || !audiences.contains(kind.audience())) {
                throw new BusinessRuleException("email-kind-not-allowed",
                        "Цей вид листів не можна змінити для вашого облікового запису: " + kind.label() + ".");
            }
        }
        EmailPreference saved = preferences.update(accountId, enabled, parsed, expectedVersion);
        return view(accountId, me, tokenRoles, saved);
    }

    /**
     * Queues a test letter to the caller's own verified address.
     *
     * @param tokenRoles the caller's roles: a dispatcher's test letter links to the dispatcher's settings page
     * @throws ServiceUnavailableException {@code email-disabled} while the channel is off
     * @throws BusinessRuleException       {@code email-missing} / {@code email-not-verified}
     * @throws TooManyRequestsException    {@code email-test-limit} over the hourly limit
     */
    public void sendTest(UUID accountId, Set<String> tokenRoles) {
        if (!channel.enabled()) {
            throw new ServiceUnavailableException("email-disabled", "Надсилання листів на сервері не налаштовано.");
        }
        MailAddresses.Verification address = verification(recipients.account(accountId));
        if (address.status() == MailAddresses.Status.MISSING) {
            throw new BusinessRuleException("email-missing", "На вашому обліковому записі немає e-mail — "
                    + "попросіть навчальну частину додати корпоративну адресу.");
        }
        if (!address.verified()) {
            throw new BusinessRuleException("email-not-verified", "Адресу не підтверджено: увійдіть через Google "
                    + "або попросіть навчальну частину вказати корпоративну адресу.");
        }
        if (!guard.mayRequestTestLetter(accountId)) {
            throw new TooManyRequestsException("email-test-limit",
                    "Забагато тестових листів. Спробуйте за годину.");
        }
        notices.toAccount(EmailKind.TEST, accountId, new TestMail(clock.instant(), tokenRoles.contains(ADMIN)),
                "test:" + accountId + ":" + UUID.randomUUID());
    }

    private View view(UUID accountId, Optional<MailRecipient> me, Set<String> tokenRoles, EmailPreference preference) {
        Set<EmailAudience> audiences = audiences(me, tokenRoles);
        List<KindSetting> kinds = Arrays.stream(EmailKind.values())
                .filter(kind -> kind.listed() && audiences.contains(kind.audience()))
                .map(kind -> new KindSetting(kind, preference.allows(kind)))
                .toList();
        return new View(accountId, channel.enabled(), channel.enabled() ? null
                : "Надсилання листів на сервері не налаштовано.", verification(me), preference.isEnabled(),
                preference.isNew() ? null : preference.getVersion(), kinds);
    }

    private MailAddresses.Verification verification(Optional<MailRecipient> me) {
        return me.map(recipient -> MailAddresses.verify(recipient.email(), recipient.googleLinked(),
                        properties.trustedDomains()))
                .orElse(new MailAddresses.Verification(null, MailAddresses.Status.MISSING, null));
    }

    /** Teacher kinds for an account linked to a teacher (by core, else by the token role), admin kinds for ADMIN. */
    private static Set<EmailAudience> audiences(Optional<MailRecipient> me, Set<String> tokenRoles) {
        Set<EmailAudience> audiences = EnumSet.noneOf(EmailAudience.class);
        boolean teacher = me.map(recipient -> recipient.teacherId() != null).orElse(tokenRoles.contains(TEACHER));
        if (teacher) {
            audiences.add(EmailAudience.TEACHER);
        }
        if (tokenRoles.contains(ADMIN) || me.map(recipient -> recipient.hasRole(ADMIN)).orElse(false)) {
            audiences.add(EmailAudience.ADMIN);
        }
        return audiences;
    }

    /** The settings page of one account; {@code version} is null until the account saved once. */
    public record View(UUID accountId, boolean channelEnabled, String channelReason,
                       MailAddresses.Verification address, boolean enabled, Long version, List<KindSetting> kinds) {
    }

    public record KindSetting(EmailKind kind, boolean enabled) {
    }
}
