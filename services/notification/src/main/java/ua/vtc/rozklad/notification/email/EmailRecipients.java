package ua.vtc.rozklad.notification.email;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import ua.vtc.rozklad.notification.config.MailChannelProperties;
import ua.vtc.rozklad.notification.core.CoreClient;
import ua.vtc.rozklad.notification.core.CoreClient.MailRecipientQuery;
import ua.vtc.rozklad.notification.core.CoreDtos.MailRecipient;
import ua.vtc.rozklad.notification.core.RedisJsonCache;

/**
 * The people behind an intent, from core's {@code GET /api/internal/mail-recipients}: core holds the accounts
 * (it reads them from the sign-in service), notification only caches the answers for
 * {@code app.mail.recipients-cache-ttl}. Addresses never travel in events. core unreachable →
 * {@code UpstreamUnavailableException}, and the intent is retried.
 */
@Component
public class EmailRecipients {

    static final String ADMIN = "ADMIN";
    private static final TypeReference<List<MailRecipient>> LIST = new TypeReference<>() {
    };

    private final CoreClient core;
    private final RedisJsonCache cache;
    private final MailChannelProperties properties;

    public EmailRecipients(CoreClient core, RedisJsonCache cache, MailChannelProperties properties) {
        this.core = core;
        this.cache = cache;
        this.properties = properties;
    }

    /** Active people an intent addresses (not yet filtered by address or settings). */
    public List<MailRecipient> of(EmailNotice notice) {
        List<MailRecipient> found = switch (notice.getTarget()) {
            case TEACHER -> cached(MailRecipientQuery.teacher(notice.getTeacherId()));
            case ADMINS -> cached(MailRecipientQuery.role(ADMIN)).stream()
                    .filter(recipient -> recipient.hasRole(ADMIN))
                    .toList();
            case ACCOUNT -> cached(MailRecipientQuery.account(notice.getAccountId()));
        };
        return found.stream().filter(MailRecipient::active).toList();
    }

    /** The caller's own account, read fresh (the settings page). */
    public Optional<MailRecipient> account(UUID accountId) {
        return core.mailRecipients(MailRecipientQuery.account(accountId)).stream()
                .filter(recipient -> accountId.equals(recipient.accountId()))
                .findFirst();
    }

    private List<MailRecipient> cached(MailRecipientQuery query) {
        List<MailRecipient> recipients = cache.get("mail-recipients:" + query.key(), LIST,
                properties.recipientsCacheTtl(), () -> core.mailRecipients(query));
        return recipients == null ? List.of() : recipients;
    }
}
